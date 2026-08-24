# rtee(1)

## Name

`rtee` - run a command while teeing stdout, stderr, stdin, command metadata, timestamps, and exit status into an append-only transcript file.

## Synopsis

```text
rtee [--add-time] [--control-stderr] [--session <log-file>] <command> [args...]
rtee -h
rtee --help
rtee --version
```

## Description

`rtee` is a small command transcript wrapper. It executes a child command, preserves the child command's live stdout and stderr behavior, and appends a structured transcript to a log file.

It is useful for agentic sessions where tool output should remain visible in the terminal while also being recorded in a durable markdown-friendly log.

`rtee` prints its own control records (`##`, `cmd>`, context records, `res>`) to stdout by default so non-interactive runners such as Codex can see command framing. Use `--control-stderr` for the older behavior.

Positional arguments are always the command and its arguments. The transcript file defaults to `rtee.log`. Use `--session` or `--log` to choose a different transcript file.

For simple usage:

```bash
rtee ls
```

For a named transcript:

```bash
rtee --session baubau git status --short
```

## Install / Dev Usage

Use Scoopix app installs instead of wrapper scripts. Scoopix compiles `rtee.rs` from Git source with local `rustc`, injects the derived Git version into the binary, and installs the generated executable on `PATH`.

Source: https://github.com/raisercostin/scripts/blob/main/rtee.rs

- Public one-shot installer:

  ```bash
  deno run --allow-all https://github.com/raisercostin/scoopix/raw/refs/heads/main/scoopix.ts install main/rtee --approve-rustc-build
  rtee --version
  ```

- Public install:

  ```bash
  deno install --allow-all --force --name=scoopix https://github.com/raisercostin/scoopix/raw/refs/heads/main/scoopix.ts
  scoopix install main/rtee --approve-rustc-build
  rtee --version
  ```

- Dev install:

  ```bash
  deno install --allow-all --force --name=scoopix D:/home/raiser/work/2025-11-10--scoopix/scoopix.ts
  scoopix install main/rtee --approve-rustc-build --ignore-download-cache
  rtee --version
  ```

- Dev local:

  ```bash
  rustc --edition 2024 rtee.rs -O -o rtee
  ./rtee --version
  ```

## Quick Example

Run a command and save the interaction to `session.md`:

```bash
./rtee.exe --add-time --session session.md sh -c "printf 'hello\\n'; printf 'warn\\n' >&2; exit 3"
```

Terminal output still appears live:

```text
## 2026-08-16T06:50:57Z
00000ms cmd> sh -c 'printf '"'"'hello\n'"'"'; printf '"'"'warn\n'"'"' >&2; exit 3'
00000ms cwd> D:\home\raiser\work\2025-10-18--scripts
00000ms usr> raiser
00000ms hst> AMANTAWIN3
00000ms pid> 12345
00000ms exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
00387ms out> hello
00388ms err> warn
00408ms res> 3
## 2026-08-16T06:50:57Z
```

The same structured section is appended to `session.md`, so an agent or human can later review exactly what command ran, what it printed, and how it exited.

## Options

`--add-time`

Prefix each transcript record with elapsed milliseconds since `rtee` started.

`--control-stderr`

Print `rtee` control records to stderr instead of stdout. Child stdout and stderr keep their normal live stream destinations.

`--session <log-file>`, `--log <log-file>`

Set the transcript file explicitly. Without this flag, `rtee` writes to `rtee.log`.

`-h`, `--help`

Show built-in help and exit.

`--version`

Show the `rtee` version and exit.

## Arguments

`<command>`

The executable to run.

`[args...]`

Arguments passed verbatim to the child command.

## Record Format

Each invocation appends a new section to the transcript:

```text
## 2026-08-15T20:42:13Z

cmd> git status --short
cwd> D:\home\raiser\work\2025-10-18--scripts
usr> raiser
hst> AMANTAWIN3
pid> 12345
exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
out> ...
err> ...
res> 0
## 2026-08-15T20:42:14Z
```

With `--add-time`, records include elapsed milliseconds:

```text
00000ms cmd> git status --short
00000ms cwd> D:\home\raiser\work\2025-10-18--scripts
00000ms usr> raiser
00000ms hst> AMANTAWIN3
00000ms pid> 12345
00000ms exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
00043ms out>  M src/main.ts
00044ms out> ?? notes.md
00048ms res> 0
```

Record tags:

- `cmd>` rendered command line.
- `cwd>` current working directory where `rtee` was started.
- `usr>` user name from the process environment.
- `hst>` host name from the process environment or `hostname` command.
- `pid>` `rtee` process id.
- `exe>` resolved `rtee` executable path.
- `in >` stdin forwarded to the child command.
- `out>` stdout from the child command.
- `err>` stderr from the child command.
- `res>` child process exit code.
- `## <iso-time>` UTC start and end timestamps.

## Examples

Record a repository inspection command:

```bash
./rtee.exe --add-time --session session.md git status --short
```

Example transcript:

```text
## 2026-08-16T07:00:00Z

00000ms cmd> git status --short
00000ms cwd> D:\home\raiser\work\2025-10-18--scripts
00000ms usr> raiser
00000ms hst> AMANTAWIN3
00000ms pid> 12345
00000ms exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
00021ms out>  M src/main.ts
00022ms out> ?? notes.md
00025ms res> 0
## 2026-08-16T07:00:00Z
```

Record a test command:

```bash
./rtee.exe --add-time --session session.md deno check src/main.ts
```

Example transcript:

```text
## 2026-08-16T07:01:00Z

00000ms cmd> deno check src/main.ts
00000ms cwd> D:\home\raiser\work\2025-10-18--scripts
00000ms usr> raiser
00000ms hst> AMANTAWIN3
00000ms pid> 12345
00000ms exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
00142ms out> Check file:///D:/project/src/main.ts
00148ms res> 0
## 2026-08-16T07:01:00Z
```

Record a shell command through the chosen shell explicitly:

```bash
./rtee.exe --add-time --session session.md sh -c "printf 'hello\\n'; printf 'warn\\n' >&2"
```

Example transcript:

```text
## 2026-08-16T07:02:00Z

00000ms cmd> sh -c 'printf '"'"'hello\n'"'"'; printf '"'"'warn\n'"'"' >&2'
00000ms cwd> D:\home\raiser\work\2025-10-18--scripts
00000ms usr> raiser
00000ms hst> AMANTAWIN3
00000ms pid> 12345
00000ms exe> D:\home\raiser\work\2025-10-18--scripts\rtee.exe
00016ms out> hello
00017ms err> warn
00019ms res> 0
## 2026-08-16T07:02:00Z
```

## Exit Status

`rtee` exits with the wrapped command's exit code.

If `rtee` itself cannot open the log file, spawn the child command, or manage its IO threads, it exits with an error from the runtime.

## Files

`rtee.rs`

Rust implementation used to build the current `rtee.exe`.

`rtee.exe`

Windows executable built from `rtee.rs`.

`rtee.zig`

Experimental Zig comparison implementation. It was not verified in this session because `zig` was not installed.

## Development

### rtee Compaction Handoff

Objective:

- Provide a small Windows-friendly Unix-style command transcript wrapper.
- Preserve live command output while appending a readable markdown transcript.
- Support agentic tool-use sessions where command evidence matters.

Important details:

- Use `./rtee.exe --add-time <command>` for default `rtee.log` logging.
- Use `./rtee.exe --add-time --session session.md <command>` for named transcripts.
- Positional arguments are always the child command and its args.
- Wrapped command options do not need `--` because rtee options are parsed before the child command.
- Do not pipe raw binary payloads through `rtee`; transcript tags corrupt byte-perfect streams.
- Use byte-preserving tools for archives, databases, images, PDFs, and other binary payloads.
- Current verified implementation is Rust: `rtee.rs` / `rtee.exe`.
- Zig comparison exists as `rtee.zig`, but it was not compiled or verified because `zig` was not installed.

Completed:

- Created `rtee.rs` and compiled `rtee.exe`.
- Implemented append-only transcript logging.
- Implemented `cmd>`, `cwd>`, `usr>`, `hst>`, `pid>`, `exe>`, `in >`, `out>`, `err>`, and `res>` records.
- Implemented UTC ISO start/end timestamp section markers.
- Implemented `--add-time` elapsed millisecond prefixes.
- Implemented passthrough stdout/stderr while recording separate tagged log lines.
- Implemented stdin forwarding and `in >` logging for piped input.
- Implemented child exit-code propagation.
- Implemented shell-style rendering of `cmd>` for readability while spawning through argv directly.

Active constraints:

- Preserve the transcript format unless there is a strong reason to migrate.
- Keep logs human-readable and grep-friendly.
- Do not turn `rtee` into a binary-safe capture tool.
- Do not turn `rtee` into a PTY recorder unless a concrete interactive-command need appears.
- Keep command spawning direct; do not introduce shell parsing by default.

Blocked or unverified:

- `rtee.zig` is unverified.
- No automated test suite exists yet.
- No release packaging exists beyond the checked-in `rtee.exe`.
- stdout/stderr ordering is concurrent and not guaranteed to exactly match terminal interleaving.

Next moves:

1. Add a small smoke-test script or documented command that verifies stdout, stderr, stdin, and exit-code behavior.
2. Decide whether `rtee.zig` should be removed, verified, or kept as an experiment.
3. Keep documenting binary-safety warnings anywhere `rtee` is recommended for workflows that may touch byte-perfect artifacts.

Relevant files:

- `rtee.rs`: verified Rust source.
- `rtee.exe`: compiled Windows executable.
- `rtee.zig`: unverified Zig comparison.
- `rtee.md`: this manual and development handoff.
- `session.md`: typical transcript file name for an agentic session.

### Goals

- Keep command/tool interaction reproducible without hiding live command output.
- Make logs readable as markdown and grep-friendly plain text.
- Append, never overwrite, existing transcript history.
- Preserve stdout and stderr as separate tagged streams.
- Forward piped stdin to the child command and record it as `in >`.
- Preserve the wrapped command's exit code.
- Avoid a `--` separator requirement by parsing only leading rtee options and treating the remaining arguments as the child command.

### Design Decisions

- The transcript file defaults to `rtee.log`; `--session`/`--log` selects a named transcript.
- The log file is opened with create + append semantics.
- Start and end timestamps are written as UTC ISO-like records.
- `--add-time` is elapsed time from process start, not wall-clock timestamp per line.
- stdout and stderr are read on separate threads so a noisy stream does not block the other stream.
- Live stdout is written back to process stdout and live stderr to process stderr.
- Stream chunks are forwarded immediately, while transcript records are line-buffered for readable `out>`/`err>` entries.
- Partial final lines are still logged when streams close.
- The rendered `cmd>` line uses shell-style quoting for readability, but the child command is spawned with argv directly, not through a shell.

### Non-Goals

- Do not use `rtee` for raw binary stream capture.
- Do not pipe databases, archives, encrypted payloads, images, PDFs, or other binary artifacts through `rtee` when byte-perfect output matters.
- Do not turn `rtee` into a shell parser, terminal emulator, PTY recorder, or full `script(1)` replacement.
- Do not infer or rewrite child command arguments beyond normal argv passing.
- Do not make the transcript format JSON; the current target is human-readable session logs.
- Do not hide child command output behind a buffering layer that changes the operator's feedback loop.

### Binary Safety Warning

`rtee` prefixes transcript lines with tags such as `out>` and `err>`. This is correct for text logs and wrong for byte-perfect binary extraction.

Binary payloads should be copied with tools designed for byte preservation, such as direct file copy, archive tools, sync tools, or purpose-built transfer code.

Use `rtee` to log the command and diagnostic text around binary operations, not to transform the binary payload itself.

### Verbatim Requests Captured

These requests shaped the tool and its documentation:

- Use `./rtee.exe --add-time <command>` for default logged command execution.
- Use `./rtee.exe --add-time --session session.md <command>` when the transcript name matters.
- Keep raw binary payloads out of `rtee` because labels and text framing can corrupt streams.
- Default the transcript file to `rtee.log` so common use does not require a session name.
- Record `cmd>`, `in >`, `out>`, `err>`, and `res>` records.
- Add ISO start and end timestamps.
- Add elapsed millisecond timing with `--add-time`.
- Keep tooling practical on Windows/Git-Bash while still behaving like a small Unix-style command-line tool.

### Known Limitations

- Output ordering between stdout and stderr is concurrent and may not exactly match a PTY's combined interleaving.
- The Rust version does not allocate a pseudo-terminal, so child commands may detect a pipe instead of an interactive terminal.
- The transcript is UTF-8 lossy for logging stream lines; live output bytes are forwarded to the terminal, but logged text is human-oriented.
- The Zig version exists for comparison but is not the verified implementation.

### Build Notes

Current verified implementation:

```bash
rustc rtee.rs -O -o rtee.exe
```

Version check:

```bash
./rtee.exe --version
```

Rust source can be compiled from a local file or from content fetched over HTTP by an outer command, but `rustc` does not natively execute remote source the way Deno imports HTTP modules or JBang can run remote scripts. A remote one-shot flow needs an explicit bootstrap step that downloads the source, verifies it when appropriate, compiles it, and then runs the local binary.

Suggested smoke test:

```bash
./rtee.exe --add-time rtee-smoke.md sh -c "printf 'hello\\n'; printf 'warn\\n' >&2; exit 3"
```

Expected behavior:

- Terminal shows `hello` on stdout and `warn` on stderr.
- `rtee-smoke.md` contains `cmd>`, `out> hello`, `err> warn`, and `res> 3`.
- The `rtee.exe` process exits with code `3`.

## See Also

`tee(1)`, `script(1)`, `stdbuf(1)`
