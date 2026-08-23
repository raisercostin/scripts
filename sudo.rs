use std::env;
use std::ffi::{OsStr, c_void};
use std::fs::{self, File};
use std::io;
use std::io::Write;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::FromRawHandle;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const VERSION: &str = "0.1.1";
const CHILD_MARKER: &str = "--sudo-rs-child";

type Bool = i32;
type Dword = u32;
type Handle = *mut c_void;
type Hinstance = *mut c_void;
type Hkey = *mut c_void;
type Hwnd = *mut c_void;
type Lpcwstr = *const u16;

const SEE_MASK_NOCLOSEPROCESS: u32 = 0x0000_0040;
const SW_SHOWNORMAL: i32 = 1;
const INFINITE: Dword = 0xffff_ffff;
const GENERIC_WRITE: Dword = 0x4000_0000;
const OPEN_EXISTING: Dword = 3;
const FILE_ATTRIBUTE_NORMAL: Dword = 0x0000_0080;
const PIPE_ACCESS_INBOUND: Dword = 0x0000_0001;
const PIPE_TYPE_BYTE: Dword = 0x0000_0000;
const PIPE_READMODE_BYTE: Dword = 0x0000_0000;
const PIPE_WAIT: Dword = 0x0000_0000;
const ERROR_PIPE_CONNECTED: Dword = 535;
const INVALID_HANDLE_VALUE: Handle = -1_isize as Handle;

#[repr(C)]
struct ShellExecuteInfoW {
    cb_size: Dword,
    f_mask: u32,
    hwnd: Hwnd,
    lp_verb: Lpcwstr,
    lp_file: Lpcwstr,
    lp_parameters: Lpcwstr,
    lp_directory: Lpcwstr,
    n_show: i32,
    h_inst_app: Hinstance,
    lp_id_list: *mut c_void,
    lp_class: Lpcwstr,
    hkey_class: Hkey,
    dw_hot_key: Dword,
    h_icon_or_monitor: Handle,
    h_process: Handle,
}

#[link(name = "shell32")]
unsafe extern "system" {
    fn ShellExecuteExW(info: *mut ShellExecuteInfoW) -> Bool;
    fn IsUserAnAdmin() -> Bool;
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn AttachConsole(dw_process_id: Dword) -> Bool;
    fn FreeConsole() -> Bool;
    fn WaitForSingleObject(handle: Handle, milliseconds: Dword) -> Dword;
    fn GetExitCodeProcess(handle: Handle, exit_code: *mut Dword) -> Bool;
    fn CloseHandle(handle: Handle) -> Bool;
    fn CreateNamedPipeW(
        lp_name: Lpcwstr,
        dw_open_mode: Dword,
        dw_pipe_mode: Dword,
        n_max_instances: Dword,
        n_out_buffer_size: Dword,
        n_in_buffer_size: Dword,
        n_default_time_out: Dword,
        lp_security_attributes: *mut c_void,
    ) -> Handle;
    fn ConnectNamedPipe(h_named_pipe: Handle, lp_overlapped: *mut c_void) -> Bool;
    fn CreateFileW(
        lp_file_name: Lpcwstr,
        dw_desired_access: Dword,
        dw_share_mode: Dword,
        lp_security_attributes: *mut c_void,
        dw_creation_disposition: Dword,
        dw_flags_and_attributes: Dword,
        h_template_file: Handle,
    ) -> Handle;
}

fn usage() -> ! {
    eprintln!("Usage:");
    eprintln!("  sudo <command> [args...]");
    eprintln!("  sudo --version");
    eprintln!("  sudo --help");
    std::process::exit(2);
}

fn wide(value: &OsStr) -> Vec<u16> {
    value.encode_wide().chain(std::iter::once(0)).collect()
}

fn wide_str(value: &str) -> Vec<u16> {
    wide(OsStr::new(value))
}

fn quote_windows_arg(arg: &str) -> String {
    if arg.is_empty() || arg.bytes().any(|b| b == b' ' || b == b'\t' || b == b'"') {
        let mut result = String::from("\"");
        let mut backslashes = 0;
        for ch in arg.chars() {
            if ch == '\\' {
                backslashes += 1;
            } else if ch == '"' {
                result.push_str(&"\\".repeat(backslashes * 2 + 1));
                result.push('"');
                backslashes = 0;
            } else {
                result.push_str(&"\\".repeat(backslashes));
                backslashes = 0;
                result.push(ch);
            }
        }
        result.push_str(&"\\".repeat(backslashes * 2));
        result.push('"');
        result
    } else {
        arg.to_string()
    }
}

fn command_line(args: &[String]) -> String {
    args.iter()
        .map(|arg| quote_windows_arg(arg))
        .collect::<Vec<_>>()
        .join(" ")
}

fn is_path_like(command: &str) -> bool {
    command.contains(['/', '\\']) || Path::new(command).is_absolute()
}

fn executable_extensions(command: &str) -> Vec<String> {
    if Path::new(command).extension().is_some() {
        return vec![String::new()];
    }
    let mut extensions = env::var("PATHEXT")
        .unwrap_or_else(|_| ".COM;.EXE;.BAT;.CMD".to_string())
        .split(';')
        .filter(|value| !value.is_empty())
        .map(|value| value.to_string())
        .collect::<Vec<_>>();
    extensions.push(String::new());
    extensions
}

fn existing_executable(candidate: PathBuf) -> Option<String> {
    fs::metadata(&candidate)
        .ok()
        .filter(|metadata| metadata.is_file())
        .map(|_| candidate.display().to_string())
}

fn existing_executable_with_extensions(candidate: PathBuf, command: &str) -> Option<String> {
    if let Some(path) = existing_executable(candidate.clone()) {
        return Some(path);
    }
    if candidate.extension().is_some() {
        return None;
    }
    for extension in executable_extensions(command) {
        if extension.is_empty() {
            continue;
        }
        if let Some(path) = existing_executable(PathBuf::from(format!(
            "{}{}",
            candidate.display(),
            extension
        ))) {
            return Some(path);
        }
    }
    None
}

fn git_bash_root() -> Option<PathBuf> {
    let exe_path = env::var_os("EXEPATH").map(PathBuf::from)?;
    if exe_path
        .file_name()
        .and_then(|value| value.to_str())
        .map(|value| value.eq_ignore_ascii_case("bin"))
        .unwrap_or(false)
    {
        exe_path.parent().map(Path::to_path_buf)
    } else {
        Some(exe_path)
    }
}

fn msys_path_to_windows(value: &str) -> PathBuf {
    let bytes = value.as_bytes();
    if bytes.len() >= 2
        && bytes[0] == b'/'
        && bytes[1].is_ascii_alphabetic()
        && (bytes.len() == 2 || bytes[2] == b'/')
    {
        let drive = (bytes[1] as char).to_ascii_uppercase();
        let rest = value.get(2..).unwrap_or("").replace('/', "\\");
        return PathBuf::from(format!("{drive}:{rest}"));
    }

    if value == "/bin" || value.starts_with("/bin/") {
        if let Some(root) = git_bash_root() {
            return root
                .join("usr")
                .join(value.trim_start_matches("/bin/").trim_start_matches("bin"));
        }
    }

    if value.starts_with('/') {
        if let Some(root) = git_bash_root() {
            return root.join(value.trim_start_matches('/').replace('/', "\\"));
        }
    }

    PathBuf::from(value.replace('/', "\\"))
}

fn native_path(value: &str) -> PathBuf {
    if value.starts_with('/') {
        msys_path_to_windows(value)
    } else {
        PathBuf::from(value)
    }
}

fn path_entries() -> Vec<PathBuf> {
    let Some(path) = env::var_os("PATH") else {
        return Vec::new();
    };
    let raw = path.to_string_lossy();
    if raw.contains(';') {
        return env::split_paths(&path).collect();
    }
    raw.split(':')
        .filter(|entry| !entry.is_empty())
        .map(msys_path_to_windows)
        .collect()
}

fn bash_path_lookup(command: &str) -> Option<String> {
    if is_path_like(command) || env::var_os("MSYSTEM").is_none() {
        return None;
    }

    let bash = git_bash_root().map(|root| root.join("usr").join("bin").join("bash.exe"))?;
    let output = Command::new(bash)
        .args(["-lc", "type -P -- \"$1\"", "sudo-rs-lookup", command])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let path = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if path.is_empty() {
        return None;
    }
    existing_executable_with_extensions(native_path(&path), command)
}

fn resolve_command_path(command: &str) -> Option<String> {
    if let Some(path) = bash_path_lookup(command) {
        return Some(path);
    }

    let extensions = executable_extensions(command);
    if is_path_like(command) {
        for extension in extensions {
            if let Some(path) = existing_executable(native_path(&format!("{command}{extension}"))) {
                return Some(path);
            }
        }
        return None;
    }

    for dir in path_entries() {
        for extension in &extensions {
            if let Some(path) = existing_executable(dir.join(format!("{command}{extension}"))) {
                return Some(path);
            }
        }
    }
    None
}

fn resolve_command_args(args: &[String]) -> Vec<String> {
    let mut resolved = args.to_vec();
    if let Some(command) = args
        .first()
        .and_then(|command| resolve_command_path(command))
    {
        resolved[0] = command;
    }
    if env::var_os("SUDO_RS_DEBUG").is_some() {
        eprintln!("sudo: resolved command: {}", resolved[0]);
    }
    resolved
}

fn named_pipe_server(name: &str) -> io::Result<Handle> {
    let name = wide_str(name);
    let handle = unsafe {
        CreateNamedPipeW(
            name.as_ptr(),
            PIPE_ACCESS_INBOUND,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
            1,
            8192,
            8192,
            0,
            std::ptr::null_mut(),
        )
    };
    if handle == INVALID_HANDLE_VALUE {
        Err(io::Error::last_os_error())
    } else {
        Ok(handle)
    }
}

fn connect_pipe_server(handle: Handle) -> io::Result<()> {
    let ok = unsafe { ConnectNamedPipe(handle, std::ptr::null_mut()) != 0 };
    if ok {
        return Ok(());
    }
    let err = io::Error::last_os_error();
    if err.raw_os_error() == Some(ERROR_PIPE_CONNECTED as i32) {
        Ok(())
    } else {
        Err(err)
    }
}

fn named_pipe_writer(name: &str) -> io::Result<File> {
    let name = wide_str(name);
    let handle = unsafe {
        CreateFileW(
            name.as_ptr(),
            GENERIC_WRITE,
            0,
            std::ptr::null_mut(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            std::ptr::null_mut(),
        )
    };
    if handle == INVALID_HANDLE_VALUE {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { File::from_raw_handle(handle) })
    }
}

fn pipe_reader_thread<W>(handle: Handle, mut output: W) -> std::thread::JoinHandle<io::Result<()>>
where
    W: Write + Send + 'static,
{
    let handle = handle as usize;
    std::thread::spawn(move || {
        let handle = handle as Handle;
        connect_pipe_server(handle)?;
        let mut pipe = unsafe { File::from_raw_handle(handle) };
        io::copy(&mut pipe, &mut output)?;
        output.flush()
    })
}

fn run_command(args: &[String]) -> io::Result<i32> {
    let status = Command::new(&args[0])
        .args(&args[1..])
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()?;
    Ok(status.code().unwrap_or(1))
}

fn run_command_to_pipes(args: &[String], stdout_pipe: &str, stderr_pipe: &str) -> io::Result<i32> {
    let stdout_file = named_pipe_writer(stdout_pipe)?;
    let stderr_file = named_pipe_writer(stderr_pipe)?;
    let status = Command::new(&args[0])
        .args(&args[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout_file))
        .stderr(Stdio::from(stderr_file))
        .status()?;
    Ok(status.code().unwrap_or(1))
}

fn is_admin() -> bool {
    unsafe { IsUserAnAdmin() != 0 }
}

fn run_child(mut args: Vec<String>) -> ! {
    if args.len() < 6 || args[0] != CHILD_MARKER {
        usage();
    }
    let parent_pid = args[1].parse::<u32>().unwrap_or(0);
    let cwd = args[2].clone();
    let stdout_pipe = args[3].clone();
    let stderr_pipe = args[4].clone();
    args.drain(0..5);

    let _ = env::set_current_dir(cwd);
    unsafe {
        FreeConsole();
        AttachConsole(parent_pid);
    }

    match run_command_to_pipes(&args, &stdout_pipe, &stderr_pipe) {
        Ok(code) => std::process::exit(code),
        Err(err) => {
            eprintln!("sudo: {err}");
            std::process::exit(1);
        }
    }
}

fn elevate(args: &[String]) -> io::Result<i32> {
    let exe = env::current_exe()?;
    let cwd = env::current_dir()?;
    let nonce = format!("{}-{}", std::process::id(), chrono_free_timestamp());
    let stdout_pipe = format!(r"\\.\pipe\sudo-rs-{nonce}-stdout");
    let stderr_pipe = format!(r"\\.\pipe\sudo-rs-{nonce}-stderr");
    let stdout_handle = named_pipe_server(&stdout_pipe)?;
    let stderr_handle = named_pipe_server(&stderr_pipe)?;
    let mut elevated_args = vec![
        CHILD_MARKER.to_string(),
        std::process::id().to_string(),
        cwd.display().to_string(),
        stdout_pipe,
        stderr_pipe,
    ];
    elevated_args.extend(args.iter().cloned());

    let verb = wide_str("runas");
    let file = wide(exe.as_os_str());
    let params = wide_str(&command_line(&elevated_args));
    let directory = wide(cwd.as_os_str());
    let mut info = ShellExecuteInfoW {
        cb_size: std::mem::size_of::<ShellExecuteInfoW>() as Dword,
        f_mask: SEE_MASK_NOCLOSEPROCESS,
        hwnd: std::ptr::null_mut(),
        lp_verb: verb.as_ptr(),
        lp_file: file.as_ptr(),
        lp_parameters: params.as_ptr(),
        lp_directory: directory.as_ptr(),
        n_show: SW_SHOWNORMAL,
        h_inst_app: std::ptr::null_mut(),
        lp_id_list: std::ptr::null_mut(),
        lp_class: std::ptr::null(),
        hkey_class: std::ptr::null_mut(),
        dw_hot_key: 0,
        h_icon_or_monitor: std::ptr::null_mut(),
        h_process: std::ptr::null_mut(),
    };

    let ok = unsafe { ShellExecuteExW(&mut info) != 0 };
    if !ok {
        unsafe {
            CloseHandle(stdout_handle);
            CloseHandle(stderr_handle);
        }
        return Err(io::Error::last_os_error());
    }
    if info.h_process.is_null() {
        unsafe {
            CloseHandle(stdout_handle);
            CloseHandle(stderr_handle);
        }
        return Ok(0);
    }

    let stdout_thread = pipe_reader_thread(stdout_handle, io::stdout());
    let stderr_thread = pipe_reader_thread(stderr_handle, io::stderr());
    unsafe {
        WaitForSingleObject(info.h_process, INFINITE);
    }
    let stdout_result = stdout_thread.join().unwrap_or_else(|_| {
        Err(io::Error::new(
            io::ErrorKind::Other,
            "stdout thread panicked",
        ))
    });
    let stderr_result = stderr_thread.join().unwrap_or_else(|_| {
        Err(io::Error::new(
            io::ErrorKind::Other,
            "stderr thread panicked",
        ))
    });
    stdout_result?;
    stderr_result?;

    let mut exit_code: Dword = 1;
    unsafe {
        GetExitCodeProcess(info.h_process, &mut exit_code);
        CloseHandle(info.h_process);
    }
    Ok(exit_code as i32)
}

fn chrono_free_timestamp() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn main() {
    let args = env::args().skip(1).collect::<Vec<_>>();
    if args
        .first()
        .map(|arg| arg == "--help" || arg == "-h")
        .unwrap_or(false)
    {
        usage();
    }
    if args.first().map(|arg| arg == "--version").unwrap_or(false) {
        println!("sudo {VERSION}");
        return;
    }
    if args.first().map(|arg| arg == CHILD_MARKER).unwrap_or(false) {
        run_child(args);
    }
    if args.is_empty() {
        usage();
    }

    let args = resolve_command_args(&args);
    let result = if is_admin() {
        run_command(&args)
    } else {
        elevate(&args)
    };
    match result {
        Ok(code) => std::process::exit(code),
        Err(err) => {
            eprintln!("sudo: {err}");
            std::process::exit(1);
        }
    }
}
