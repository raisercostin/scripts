use std::env;
use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, IsTerminal, Read, Write};
use std::process::{ChildStdin, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

const PREFIX_WIDTH: usize = 3;
const VERSION: &str = "0.1.3";
const DEFAULT_LOG_FILE: &str = "rtee.log";

#[derive(Clone, Copy)]
struct FormatOptions {
    add_time: bool,
    started: Instant,
    control_stdout: bool,
}

fn usage() -> ! {
    eprintln!("Usage:");
    eprintln!("  rtee [--add-time] [--control-stderr] [--session <log-file>] <command> [args...]");
    eprintln!();
    eprintln!("Example:");
    eprintln!("  rtee git status --short");
    eprintln!("  rtee --session session.md git status --short");
    std::process::exit(2);
}

fn help() -> ! {
    eprintln!("Usage:");
    eprintln!("  rtee [--add-time] [--control-stderr] [--session <log-file>] <command> [args...]");
    eprintln!();
    eprintln!("Options:");
    eprintln!("  --add-time  Prefix each record with elapsed milliseconds since start.");
    eprintln!("  --control-stderr  Print rtee control records to stderr instead of stdout.");
    eprintln!("  --session, --log <log-file>  Set transcript file; default is rtee.log.");
    eprintln!("  --version   Show version.");
    eprintln!("  -h, --help  Show this help.");
    eprintln!();
    eprintln!("Command boundary:");
    eprintln!("  Positional arguments are always the command and its args.");
    eprintln!("  Use --session/--log to change the transcript file; default is rtee.log.");
    eprintln!();
    eprintln!("Records:");
    eprintln!("  ## <iso-time> start/end timestamp");
    eprintln!("  cmd> command line");
    eprintln!("  cwd> current working directory");
    eprintln!("  usr> user name");
    eprintln!("  hst> host name");
    eprintln!("  pid> rtee process id");
    eprintln!("  exe> rtee executable path");
    eprintln!("  in > stdin");
    eprintln!("  out> stdout");
    eprintln!("  err> stderr");
    eprintln!("  res> exit code; with --add-time, its prefix is total elapsed time");
    eprintln!();
    eprintln!("Example:");
    eprintln!("  rtee --add-time git status --short");
    eprintln!("  rtee --add-time --session session.md git status --short");
    std::process::exit(0);
}

fn version() -> ! {
    println!("rtee {}", VERSION);
    std::process::exit(0);
}

fn print_control(format: FormatOptions, text: &str) {
    if format.control_stdout {
        println!("{text}");
    } else {
        eprintln!("{text}");
    }
}

fn user_name() -> String {
    env::var("USER")
        .or_else(|_| env::var("USERNAME"))
        .or_else(|_| env::var("LOGNAME"))
        .unwrap_or_else(|_| "unknown".to_string())
}

fn host_name() -> String {
    env::var("COMPUTERNAME")
        .or_else(|_| env::var("HOSTNAME"))
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| {
            Command::new("hostname")
                .output()
                .ok()
                .and_then(|output| String::from_utf8(output.stdout).ok())
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| "unknown".to_string())
        })
}

fn context_records() -> Vec<(&'static str, String)> {
    vec![
        ("cwd", env::current_dir().map(|path| path.display().to_string()).unwrap_or_else(|_| "unknown".to_string())),
        ("usr", user_name()),
        ("hst", host_name()),
        ("pid", std::process::id().to_string()),
        ("exe", env::current_exe().map(|path| path.display().to_string()).unwrap_or_else(|_| "unknown".to_string())),
    ]
}

fn write_control_record(log: &Arc<Mutex<BufWriter<File>>>, format: FormatOptions, prefix: &str, value: &str) -> io::Result<()> {
    let record = tagged(format, prefix, value);
    write_log(log, &record)?;
    print_control(format, record.trim_end());
    Ok(())
}

fn civil_from_days(days: i64) -> (i32, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = mp + if mp < 10 { 3 } else { -9 };
    ((y + if m <= 2 { 1 } else { 0 }) as i32, m as u32, d as u32)
}

fn iso_timestamp_utc() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or(0);
    let days = seconds / 86_400;
    let day_seconds = seconds % 86_400;
    let (year, month, day) = civil_from_days(days);
    let hour = day_seconds / 3_600;
    let minute = (day_seconds % 3_600) / 60;
    let second = day_seconds % 60;
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
}

fn shell_quote(value: &str) -> String {
    if value
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b"_./:=@%+-".contains(&b))
    {
        value.to_string()
    } else {
        format!("'{}'", value.replace('\'', "'\"'\"'"))
    }
}

fn write_log(log: &Arc<Mutex<BufWriter<File>>>, line: &str) -> io::Result<()> {
    let mut log = log.lock().expect("log mutex poisoned");
    log.write_all(line.as_bytes())?;
    log.flush()
}

fn tagged(format: FormatOptions, prefix: &str, text: &str) -> String {
    let tag = format!("{:<width$}> {}", prefix, text, width = PREFIX_WIDTH);
    if format.add_time {
        format!("{:05}ms {}\n", format.started.elapsed().as_millis(), tag)
    } else {
        format!("{}\n", tag)
    }
}

fn tag_prefix(format: FormatOptions, prefix: &str) -> String {
    let tag = format!("{:<width$}> ", prefix, width = PREFIX_WIDTH);
    if format.add_time {
        format!("{:05}ms {}", format.started.elapsed().as_millis(), tag)
    } else {
        tag
    }
}

fn write_stream_chunk<W: Write>(
    output: &mut W,
    format: FormatOptions,
    prefix: &str,
    chunk: &[u8],
    at_line_start: &mut bool,
) -> io::Result<()> {
    for byte in chunk {
        if *at_line_start {
            output.write_all(tag_prefix(format, prefix).as_bytes())?;
            *at_line_start = false;
        }

        output.write_all(&[*byte])?;
        if *byte == b'\n' {
            *at_line_start = true;
        }
    }
    output.flush()
}

fn pump_output<R, W>(
    mut reader: R,
    mut output: W,
    log: Arc<Mutex<BufWriter<File>>>,
    format: FormatOptions,
    prefix: &'static str,
) -> io::Result<()>
where
    R: Read,
    W: Write,
{
    let mut buffer = [0_u8; 8192];
    let mut pending = Vec::new();
    let mut at_line_start = true;

    loop {
        let bytes = reader.read(&mut buffer)?;
        if bytes == 0 {
            break;
        }

        write_stream_chunk(&mut output, format, prefix, &buffer[..bytes], &mut at_line_start)?;

        pending.extend_from_slice(&buffer[..bytes]);
        while let Some(pos) = pending.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = pending.drain(..=pos).collect();
            let text = String::from_utf8_lossy(&line);
            let text = text.trim_end_matches(['\r', '\n']);
            write_log(&log, &tagged(format, prefix, text))?;
        }
    }

    if !pending.is_empty() {
        let text = String::from_utf8_lossy(&pending);
        let text = text.trim_end_matches(['\r', '\n']);
        write_log(&log, &tagged(format, prefix, text))?;
    }

    Ok(())
}

fn pump_stdin(mut child_stdin: ChildStdin, log: Arc<Mutex<BufWriter<File>>>, format: FormatOptions) -> io::Result<()> {
    let stdin = io::stdin();
    let mut stdin = stdin.lock();
    let mut buffer = [0_u8; 8192];
    let mut pending = Vec::new();

    loop {
        let bytes = stdin.read(&mut buffer)?;
        if bytes == 0 {
            break;
        }

        child_stdin.write_all(&buffer[..bytes])?;
        child_stdin.flush()?;

        pending.extend_from_slice(&buffer[..bytes]);
        while let Some(pos) = pending.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = pending.drain(..=pos).collect();
            let text = String::from_utf8_lossy(&line);
            let text = text.trim_end_matches(['\r', '\n']);
            write_log(&log, &tagged(format, "in", text))?;
            eprint!("{}", tagged(format, "in", text));
        }
    }

    if !pending.is_empty() {
        let text = String::from_utf8_lossy(&pending);
        let text = text.trim_end_matches(['\r', '\n']);
        write_log(&log, &tagged(format, "in", text))?;
        eprint!("{}", tagged(format, "in", text));
    }

    Ok(())
}

fn main() -> io::Result<()> {
    let mut args = env::args().skip(1).collect::<Vec<_>>();
    if args.first().map(|arg| arg == "--help" || arg == "-h").unwrap_or(false) {
        help();
    }
    if args.first().map(|arg| arg == "--version").unwrap_or(false) {
        version();
    }

    if args.is_empty() {
        usage();
    }

    let mut add_time = false;
    let mut control_stdout = true;
    let mut explicit_log_file = None;
    loop {
        match args.first().map(String::as_str) {
            Some("--add-time") => {
                args.remove(0);
                add_time = true;
            }
            Some("--control-stdout") => {
                args.remove(0);
                control_stdout = true;
            }
            Some("--control-stderr") => {
                args.remove(0);
                control_stdout = false;
            }
            Some("--session") | Some("--log") => {
                let flag = args.remove(0);
                if args.is_empty() {
                    eprintln!("{flag} requires a log-file value");
                    usage();
                }
                explicit_log_file = Some(args.remove(0));
            }
            _ => break,
        }
    }

    if args.is_empty() {
        usage();
    }

    if args.first().map(|arg| arg == "--help" || arg == "-h").unwrap_or(false) {
        help();
    }
    if args.first().map(|arg| arg == "--version").unwrap_or(false) {
        version();
    }

    let format = FormatOptions { add_time, started: Instant::now(), control_stdout };

    let log_file = explicit_log_file.unwrap_or_else(|| DEFAULT_LOG_FILE.to_string());
    let command = args.remove(0);
    let rendered_command = std::iter::once(command.as_str())
        .chain(args.iter().map(String::as_str))
        .map(shell_quote)
        .collect::<Vec<_>>()
        .join(" ");

    let file = OpenOptions::new().create(true).append(true).open(log_file)?;
    let log = Arc::new(Mutex::new(BufWriter::new(file)));

    let started_at = iso_timestamp_utc();
    write_log(&log, &format!("\n## {}\n\n", started_at))?;
    print_control(format, &format!("## {}", started_at));
    write_control_record(&log, format, "cmd", &rendered_command)?;
    for (prefix, value) in context_records() {
        write_control_record(&log, format, prefix, &value)?;
    }

    let mut child = Command::new(&command)
        .args(&args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    let child_stdin = child.stdin.take().expect("child stdin unavailable");
    let child_stdout = child.stdout.take().expect("child stdout unavailable");
    let child_stderr = child.stderr.take().expect("child stderr unavailable");

    let stdin_thread = if io::stdin().is_terminal() {
        drop(child_stdin);
        None
    } else {
        let stdin_log = Arc::clone(&log);
        Some(thread::spawn(move || pump_stdin(child_stdin, stdin_log, format)))
    };

    let stdout_log = Arc::clone(&log);
    let stdout_thread = thread::spawn(move || pump_output(child_stdout, io::stdout(), stdout_log, format, "out"));

    let stderr_log = Arc::clone(&log);
    let stderr_thread = thread::spawn(move || pump_output(child_stderr, io::stderr(), stderr_log, format, "err"));

    let status = child.wait()?;

    if let Some(stdin_thread) = stdin_thread {
        match stdin_thread.join() {
            Ok(Ok(())) => {}
            Ok(Err(error)) => return Err(error),
            Err(_) => return Err(io::Error::new(io::ErrorKind::Other, "stdin thread panicked")),
        }
    }

    for result in [stdout_thread.join(), stderr_thread.join()] {
        match result {
            Ok(Ok(())) => {}
            Ok(Err(error)) => return Err(error),
            Err(_) => return Err(io::Error::new(io::ErrorKind::Other, "worker thread panicked")),
        }
    }

    let code = status.code().unwrap_or(1);
    write_log(&log, &tagged(format, "res", &code.to_string()))?;
    print_control(format, tagged(format, "res", &code.to_string()).trim_end());
    let ended_at = format!("## {}\n", iso_timestamp_utc());
    write_log(&log, &ended_at)?;
    print_control(format, ended_at.trim_end());
    std::process::exit(code);
}
