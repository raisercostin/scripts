use std::env;
use std::ffi::{c_void, OsStr};
use std::fs::{self, File};
use std::io;
use std::io::{Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::process::{Command, Stdio};

const VERSION: &str = "0.1.0";
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
extern "system" {
    fn ShellExecuteExW(info: *mut ShellExecuteInfoW) -> Bool;
    fn IsUserAnAdmin() -> Bool;
}

#[link(name = "kernel32")]
extern "system" {
    fn AttachConsole(dw_process_id: Dword) -> Bool;
    fn FreeConsole() -> Bool;
    fn WaitForSingleObject(handle: Handle, milliseconds: Dword) -> Dword;
    fn GetExitCodeProcess(handle: Handle, exit_code: *mut Dword) -> Bool;
    fn CloseHandle(handle: Handle) -> Bool;
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
    args.iter().map(|arg| quote_windows_arg(arg)).collect::<Vec<_>>().join(" ")
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

fn run_command_to_files(args: &[String], stdout_path: &Path, stderr_path: &Path) -> io::Result<i32> {
    let stdout_file = File::create(stdout_path)?;
    let stderr_file = File::create(stderr_path)?;
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
    let stdout_path = args[3].clone();
    let stderr_path = args[4].clone();
    args.drain(0..5);

    let _ = env::set_current_dir(cwd);
    unsafe {
        FreeConsole();
        AttachConsole(parent_pid);
    }

    match run_command_to_files(&args, Path::new(&stdout_path), Path::new(&stderr_path)) {
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
    let stdout_path = env::temp_dir().join(format!("sudo-rs-{nonce}.stdout"));
    let stderr_path = env::temp_dir().join(format!("sudo-rs-{nonce}.stderr"));
    let mut elevated_args = vec![
        CHILD_MARKER.to_string(),
        std::process::id().to_string(),
        cwd.display().to_string(),
        stdout_path.display().to_string(),
        stderr_path.display().to_string(),
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
        return Err(io::Error::last_os_error());
    }
    if info.h_process.is_null() {
        return Ok(0);
    }

    unsafe {
        WaitForSingleObject(info.h_process, INFINITE);
        let mut exit_code: Dword = 1;
        GetExitCodeProcess(info.h_process, &mut exit_code);
        CloseHandle(info.h_process);
        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let _ = File::open(&stdout_path).and_then(|mut file| file.read_to_end(&mut stdout));
        let _ = File::open(&stderr_path).and_then(|mut file| file.read_to_end(&mut stderr));
        let _ = io::stdout().write_all(&stdout);
        let _ = io::stdout().flush();
        let _ = io::stderr().write_all(&stderr);
        let _ = io::stderr().flush();
        let _ = fs::remove_file(&stdout_path);
        let _ = fs::remove_file(&stderr_path);
        Ok(exit_code as i32)
    }
}

fn chrono_free_timestamp() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn main() {
    let args = env::args().skip(1).collect::<Vec<_>>();
    if args.first().map(|arg| arg == "--help" || arg == "-h").unwrap_or(false) {
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

    let result = if is_admin() { run_command(&args) } else { elevate(&args) };
    match result {
        Ok(code) => std::process::exit(code),
        Err(err) => {
            eprintln!("sudo: {err}");
            std::process::exit(1);
        }
    }
}
