# tvchan

`tvchan` is a cross-platform command-line utility for inspecting and editing Samsung TV channel list files (`.scm` and `.zip`) using JBang and Java. It provides easy export (SCM→CSV/TSV/JSON/Table) and plans for re-import (CSV→SCM) to streamline channel ordering and metadata adjustments.

---

## Prerequisites

* Java 17+ or later (On win: scoop install java/temurin24-jdk)
* [JBang](https://www.jbang.dev/) installed (On win: scoop install jbang)

---

## Installation

You can run directly from the script without installing:
```bash
jbang https://github
Usage: tvchan [-hV] [COMMAND]
Works with Samsung .scm channel archives. Inspired by chansort.
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  files              List all files inside the SCM archive
  channels, scm2csv  Dump channel list (SCM -> CSV/TSV/JSON/table)
  legend             Show descriptions for each output column
  csv2scm            Build a Samsung .scm file from edited CSV
```

Or clone this repository and invoke:

```bash
git clone https://github.com/youruser/tvchan.git
dd tvchan
jbang --enable-native-access=ALL-UNNAMED tvchan.java <command>
```

---

## Commands

### `scm2csv` (alias: `channels`)

Export channel lists from a `.scm`/`.zip` to CSV, TSV, JSON or console table.

```bash
jbang ... tvchan.java scm2csv <input.scm> [options]
```

**Options:**

| Option         | Description                                  | Default                      |
| -------------- | -------------------------------------------- | ---------------------------- |
| `--format`     | Output format: `table`, `csv`, `tsv`, `json` | `csv`                        |
| `--columns`    | Comma-separated list of columns to show      | `Channel,Name,Short,Quality` |
| `--allColumns` | Show all available columns                   | `false`                      |
| `--skipHeader` | Do not print the header row in table/CSV/TSV | `false`                      |
| `--sortBy`     | Column to sort by (numeric sort if integer)  | `Channel`                    |

**Example (console table):**

```bash
λ jbang --java-options="--enable-native-access=ALL-UNNAMED" \
    tvchan.java scm2csv channel_list_UE48H8000_1401.scm --format table
```

```
Channel | Name                       | Short | Quality
--------+----------------------------+-------+-----------
111     | TVR 1 HD                   |       | 1080p/HD
112     | TVR 2 HD                   |       | 1080p/HD
113     | TVR 3                      |       | 720p/SD
...     | ...                        | ...   | ...
```

---

### `csv2scm`

*(Work in progress)* Import an edited CSV back into a Samsung `.scm` file.

```bash
jbang ... tvchan.java csv2scm <template.scm|.zip> <input.csv> --output <output.scm>
```

**TODO:**

* Parse CSV edits (order, names, flags)
* Patch binary `map-*.dat` entries
* Repackage into `.scm` or `.zip`

---

## Columns & Legend

By default `scm2csv` shows only the four most useful columns:

* **Channel** – the logical channel number (button on the remote)
* **Name**    – full channel name
* **Short**   – abbreviated name (if provided)
* **Quality** – resolution + HD/SD (e.g. `1080p/HD`)

Pass `--allColumns` to see every available field (PIDs, frequencies, flags, raw index, etc.).

---

## Contributing

Contributions, bug reports, and feature requests welcome! Please open an issue or submit a pull request.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
