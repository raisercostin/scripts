# DEVLOG

## 2026-01-10: Migrate CLI logging to RichCli
**Agent:** Codex | **Role:** Implementer | **Goal:** Replace RichLogback usage with RichCli entrypoint and align CLI options.

### Summary
Migrated `zild`, `mgit`, and `xmvn` to use `RichCli.main(..., Supplier)` so logging is configured once at entry. Removed `RichLogback`, added `RichCli` utilities, and updated mgit documentation to match the new logging guidance.

### Key Changes

| Area | Type | Description |
|------|------|-------------|
| Code | modified | `zild.java` - switch to RichCli main callback and BaseOptions |
| Code | modified | `mgit.java` - switch to RichCli main callback and BaseOptions |
| Code | modified | `xmvn.java` - switch to RichCli main callback and BaseOptions |
| Code | created | `com/namekis/utils/RichCli.java` - RichCli utility |
| Code | created | `com/namekis/utils/RichTest.java` - test helper |
| Code | created | `com/namekis/utils/RichTestCli.java` - test CLI helper |
| Code | deleted | `com/namekis/utils/RichLogback.java` - removed legacy logger helper |
| Docs | modified | `mgit.md` - update logging guidance to RichCli |

### Commits

| Repo | Commit | Type | Description |
|------|--------|------|-------------|
| project | this | refactor | migrate cli logging to RichCli |

### Verification (Walkthrough)
To verify this session's work:
1. `jbang zild.java --help`
2. `jbang mgit.java --help`
3. `jbang xmvn.java --help`

### Meta (Reflections)
- **Good**: Single-entry logging reduced repeated logback configuration.
- **Bad**: None.
- **Ugly**: None.

### Origin
Full conversation archived in `.history/2026-01-10-richcli-migration.md`.

## 2026-08-24: Native sudo and rtee Provenance
**Agent:** OpenCode GPT-5.5 | **Role:** Implementer | **Goal:** Make Windows elevation and command transcripts reliable for Scoopix-installed tools.

### Summary
Improved the native Windows `sudo` helper and `rtee` transcript recorder used by Scoopix. `sudo` now resolves caller-context executables before UAC elevation and streams elevated stdout/stderr through named pipes. `rtee` now records execution context at transcript start.

### Key Changes

| Area | Type | Description |
|------|------|-------------|
| Code | modified | `sudo.rs` - resolves executable paths before elevation, handles Git Bash path conversion, streams elevated output through named pipes, keeps elevated stdin closed, and bumps to `0.1.1`. |
| Docs | modified | `sudo.md` - documents named-pipe output streaming, caller-context resolution with `type -P`, and stdin behavior. |
| Code | modified | `rtee.rs` - adds `cwd>`, `usr>`, `hst>`, `pid>`, and `exe>` context records and bumps to `0.1.3`. |
| Docs | modified | `rtee.md` - documents the new transcript context records and examples. |

### Commits

| Repo | Commit | Type | Description |
|------|--------|------|-------------|
| scripts | c056b07 | fix | improve sudo elevation IO and command resolution |
| scripts | d3268d9 | feat | record rtee execution context |

### Verification

| Command | Result |
|---------|--------|
| `rustc --edition 2024 sudo.rs -O -o ...` | Passed |
| `scoopix install ../2025-10-18--scripts/sudo.rs --name sudo3 --as sudo3 --approve-rustc-build` | Passed |
| `sudo3 ps -faW` | Passed; resolved Git Bash `ps.exe` before elevation |
| `scoopix upgrade dev/rtee --approve-rustc-build --ignore-download-cache` | Passed |
| `rtee --version` | Reported `0.1.3-20260824.195.main.gd3268d9` |

### Meta
- **Good**: Direct Scoopix installs made it fast to test the current `sudo.rs` without changing bucket metadata.
- **Bad**: `sudo2 rtee ps -faW` remains surprising because only the first command is resolved before elevation; `rtee sudo2 ps -faW` is the expected composition.
- **Ugly**: Windows UAC via `ShellExecuteExW("runas")` still triggers shell integrations such as Google Drive warnings; avoiding that requires a different broker/service architecture.
