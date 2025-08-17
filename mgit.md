# mgit

## Requirements

### **Global options / behaviors**

* Must accept `--repos=...` (comma-separated subdirs) to limit to specific repos.

  * Applies to all subcommands; must be respected (don’t ignore it on status/commit/etc).
* Must accept `--exclude=...` (comma-separated names) to exclude specific subdirs/repos from all operations.
* Must work from any directory (never require cwd = project root).
* ASCII-only for all output (no Unicode punctuation).
* Logging must always show exact git command run at INFO level, in a form that can be copy-pasted and run manually.
* All code should minimize duplication (e.g., all repo-finding/filtering must go through a single method).
* Use `RichLogback` for log configuration, with standard flags: verbosity (multi-level), color, quiet.

### **status**

* Command: `mgit status`
* Output:

  * **One line per repo**:

    * Format: `<repo>/<branch>/#<hash> [STATUS]`
    * Example: `myrepo/main/#a1b2c3d4 [CLEAN]`
  * Dirty files (if any) are shown indented below the repo line.
  * Use ANSI colors for status tags:

    * \[CLEAN] — green
    * \[DIRTY] — red
    * \[AHEAD n] — yellow
    * \[BEHIND n] — magenta
    * \[LOCAL] — cyan (branch exists only locally, not pushed/tracked)
  * **Legend**: By default, show legend+count per status at the end; optional flag `--show-legend` to toggle legend.

    * Example: `4 [CLEAN]   : No action needed.`
* **Counts** for each status must be displayed at end, one per line (with color).
* Must only run fast checks by default:

  * `git status --porcelain` (for dirty)
  * `git rev-list --left-right --count HEAD...@{u}` (ahead/behind)
* Must **not** compare against default branch (main) by default; only if explicitly requested by a future flag.
* Never slow down status with extra git calls unless user requests it.
* Must not report false \[CLEAN] when remote default branch advanced; but must be fast.



### **checkout -b**

* Command: `mgit checkout -b <branch>`
* Must only create new branch in repos with local changes (i.e., “dirty”).
* Must **not** create branch in clean repos.
* Output success/failure per repo.



### **commit**

* Command: `mgit commit -am <msg>`
* Commits all staged and unstaged changes in each repo.
* Skips repos in detached HEAD.
* Only commits in repos that are dirty (with local changes).
* Per-repo output:

  * Green `[repo] committed` if commit was successful
  * Error log if failed



### **push**

* Command: `mgit push`
* Pushes current branch for each repo.
* If branch has no upstream, must run:
  `git push -u origin <branch>`
* If nothing to push, skips repo.
* Output success/failure per repo, colored.



### **General**

* All subcommands must accept and respect `--repos` and `--exclude` (no exceptions).
* Must have a **single, non-duplicated implementation** of repo discovery and filtering.
* All output must use only ASCII punctuation.
* Print all commands run (for traceability/copy-paste) at INFO.
* No swallowing exceptions:

  * Only catch exceptions for **specific, expected causes** (never generalize or log-and-continue unless intentional).
  * Wrap checked exceptions in `RuntimeException` as early as possible.



### **Other**

* Must be able to add more subcommands in future (e.g., PR detection), via a single subcommand registration point.
* Never require the user to be in a particular directory—should run from any path as long as the target repos are accessible.



**This is your mgit-specific requirements list, synthesized from all your inputs.**
Let me know if you want this as a file or need to further refine/split for implementation.
