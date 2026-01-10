# 2026-01-10: RichCli Migration

## Context
The session started with a request to migrate `zild.java` from `RichLogback` to `RichCli`, with instructions to follow `.gene/` practices. A later request expanded the migration to other files (excluding `samples/`), and to remove the legacy `RichLogback`.

## Conversation
- User required reading `.gene/README.md` and full `.gene` context before any work.
- Migration needed to use `RichCli.main(..., callback)` rather than manual logback configuration in each command.
- User allowed removing the Jansi dependency where no longer needed.
- Additional files (`mgit`, `xmvn`) were identified and migrated, and mgit docs were updated.
- User requested deletion of `RichLogback` and then sanity checks via `jbang ... --help`.

## Outcome
- `zild`, `mgit`, and `xmvn` now use `RichCli` for logging via a single entrypoint and share `RichCli.BaseOptions`.
- `RichLogback` removed; `RichCli`, `RichTest`, and `RichTestCli` added.
- Sanity checks run for `zild`, `mgit`, and `xmvn`.

## References
- Commits: this
- Files changed: `zild.java`, `mgit.java`, `xmvn.java`, `mgit.md`, `com/namekis/utils/RichCli.java`, `com/namekis/utils/RichTest.java`, `com/namekis/utils/RichTestCli.java`, `com/namekis/utils/RichLogback.java`, `DEVLOG.md`, `.history/2026-01-10-richcli-migration.md`
- Practices updated: none
