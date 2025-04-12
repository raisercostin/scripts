#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write
/**
 * Desh Script — Backup installed APKs from a OnePlus6T using adb.
 *
 * This script uses Cliffy for comprehensive command processing.
 *
 * Usage:
 *   deno run --allow-run --allow-read --allow-write desh_apps.ts backup
 */

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { Shell } from "https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/desh.ts";

const { shell } = new Shell("info");

async function backupApks() {
  // Create a backup directory with a timestamped name.
  const now = new Date();
  const timestamp = now.toISOString().replace(/[:\-\.]/g, "");
  const backupDir = `apk_backup_${timestamp}`;
  await Deno.mkdir(backupDir, { recursive: true });
  console.log(`Backup directory created: ${backupDir}`);

  // List installed APK paths with "adb shell pm list packages -f"
  // Expected output format: package:/data/app/<package-path>/base.apk=<package.name>
  const output = await shell`adb shell pm list packages -f`;
  if (!output) {
    console.error("No output from adb. Ensure your device is connected and adb is working.");
    return;
  }

  const lines = output.split("\n").filter((line) => line.trim().length > 0);
  for (const line of lines) {
    // Remove "package:" prefix and split the remaining string by '='.
    const [apkPathRaw, packageNameRaw] = line.replace(/^package:/, "").split("=");
    if (!apkPathRaw || !packageNameRaw) continue;
    const apkPath = apkPathRaw.trim();
    const packageName = packageNameRaw.trim();

    console.log(`Pulling APK for ${packageName} from ${apkPath} ...`);
    try {
      await shell`adb pull ${apkPath} ${backupDir}/${packageName}.apk`;
    } catch (error) {
      console.error(`Failed to pull ${apkPath} for ${packageName}: ${error}`);
    }
  }
  console.log(`Backup complete. All APKs saved in ./${backupDir}/`);
}

await new Command()
  .name("desh_apps")
  .version("0.1")
  .description("Backup all installed APKs from a OnePlus6T using adb")
  .command(
    "backup",
    new Command()
      .description("Transfer all installed APKs using adb")
      .action(async () => {
        await backupApks();
      }),
  )
  .example(
    "Backup installed APKs",
    "desh_apps backup",
  )
  .parse(Deno.args);
