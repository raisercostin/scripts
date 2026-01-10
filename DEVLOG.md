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
