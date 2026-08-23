# sudo(1)

## Name

`sudo` - Windows UAC elevation helper implemented as a native executable.

## Synopsis

```text
sudo <command> [args...]
sudo --version
sudo --help
```

## Description

This is a Windows-oriented `sudo` helper. It is not Unix `sudo`; it uses Windows UAC elevation. If the current process is already elevated, it runs the command directly. Otherwise it resolves the requested executable in the caller context, relaunches itself with the Windows `runas` verb, streams the elevated command's stdout/stderr back through named pipes, and exits with the elevated command's exit code.

The native executable avoids shell-specific `sudo`, `sudo.cmd`, and `sudo.ps1` resolution issues. This matters for tools such as `rtee`, Rust, Deno, and other process-spawning programs that call `CreateProcess` directly instead of going through Git Bash or PowerShell command resolution.

## Install / Dev Usage

Use Scoopix app installs instead of wrapper scripts. Scoopix compiles `sudo.rs` from Git source with local `rustc`, injects the derived Git version into the binary, and installs `sudo.exe` on `PATH`.

Source: https://github.com/raisercostin/scripts/blob/main/sudo.rs

- Public one-shot installer:

  ```bash
  deno run --allow-all https://github.com/raisercostin/scoopix/raw/refs/heads/main/scoopix.ts install main/sudo --approve-rustc-build
  sudo --version
  ```

- Public install:

  ```bash
  deno install --allow-all --force --name=scoopix https://github.com/raisercostin/scoopix/raw/refs/heads/main/scoopix.ts
  scoopix install main/sudo --approve-rustc-build
  sudo --version
  ```

- Dev install:

  ```bash
  deno install --allow-all --force --name=scoopix D:/home/raiser/work/2025-11-10--scoopix/scoopix.ts
  scoopix install main/sudo --approve-rustc-build --ignore-download-cache
  sudo --version
  ```

- Dev local:

  ```bash
  rustc sudo.rs -O -o sudo.exe
  ./sudo.exe --version
  ```

## Examples

Run `ps` elevated:

```bash
sudo ps
```

Record an elevated command through `rtee`:

```bash
rtee --session sudo-session.md sudo ps
```

## Notes

Elevated commands run without forwarded stdin. In the non-admin path, stdout and stderr are streamed back through per-run Windows named pipes. This makes process-spawning tools such as `rtee` capture elevated output reliably while still showing output during the elevated command.

When launched from Git Bash/MSYS, `sudo` asks Bash for the executable path with `type -P` before elevation. This makes `sudo ps -faW` run the same executable that the caller shell would have run, instead of resolving `ps` later in the elevated environment.
