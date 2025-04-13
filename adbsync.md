# adbsync apps

This copies all your android apps in a local dir.

## Install
```
deno install --global -f --allow-run --allow-read --allow-write --name adbsync https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/adbsync.ts

adbsync --dest e:\2025-05-12--oneplus6t-root\data\apps --rollOlder
```

## Prerequisites

1. connect phone on usb with usb debugging enabled.
2. `scoop install adb`
3. `adb devices`
   * daemon not running; starting now at tcp:5037
   * daemon started successfully
   List of devices attached
   e66c9b8a        unauthorized
4. `deno --allow-write --allow-run --allow-read adbsync.ts app --dest e:\2025-05-12--oneplus6t-root\data\apps --rollOlder`


## Usage

```shell
λ deno --allow-all adbsync.ts

  Usage:   adbsync
  Version: 0.1

  Description:

    Sync all installed APKs from a OnePlus6T using adb.

  Options:

    -h, --help     - Show this help.
    -V, --version  - Show the version number for this program.

  Commands:

    app  - Transfer all installed APKs to a destination directory.


λ deno --allow-all adbsync.ts app -h

  Usage:   adbsync app
  Version: 0.1

  Description:

    Transfer all installed APKs to a destination directory.

  Options:

    -h, --help                - Show this help.
    --dest       <directory>  - Destination directory for APK sync  (Default: "./apk_sync")
    --timestamp               - Append a timestamp to filenames     (Default: false)
    --force                   - Force overwrite if file exists      (Default: false)
```