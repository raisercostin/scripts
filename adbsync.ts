#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { Shell } from "https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/desh.ts";

const { shell } = new Shell("info");

async function syncApps(dest: string, addTimestamp: boolean, forceOverwrite: boolean) {
  await Deno.mkdir(dest, { recursive: true });
  const output = await shell`adb shell pm list packages -f`;
  if (!output) {
    console.error("No output from adb. Ensure your device is connected and adb is working.");
    return;
  }
  const lines = output.split("\n").filter(line => line.trim().length > 0);
  for (const line of lines) {
    const trimmedLine = line.replace(/^package:/, "").trim();
    const lastEqualIndex = trimmedLine.lastIndexOf("=");
    if (lastEqualIndex === -1) continue;
    const fullPath = trimmedLine.substring(0, lastEqualIndex).trim();
    const packageName = trimmedLine.substring(lastEqualIndex + 1).trim();
    // Extract the app directory path by removing the file name portion.
    const appDir = fullPath.substring(0, fullPath.lastIndexOf("/"));
    let localDirName = packageName;
    if (addTimestamp) {
      const now = new Date();
      const timeStamp = now.toISOString().replace(/[:\-\.]/g, "");
      localDirName = `${packageName}_${timeStamp}`;
    }
    const destDir = `${dest}/${localDirName}`;
    let exists = false;
    try {
      await Deno.stat(destDir);
      exists = true;
    } catch (error) {
      if (!(error instanceof Deno.errors.NotFound)) {
        console.error(`Error checking directory ${destDir}: ${error}`);
        continue;
      }
    }
    if (exists && !forceOverwrite) continue;
    try {
      await shell`adb pull ${appDir} ${destDir}`;
      console.log(`Synced ${packageName} from ${appDir} to ${destDir} ... ok`);
    } catch (error) {
      console.error(`Error syncing ${packageName} from ${appDir} to ${destDir}: ${error}`);
    }
  }
  console.log("Sync complete.");
}

await new Command()
  .name("adbsync")
  .version("0.1")
  .description("Sync all installed app directories from a OnePlus6T using adb.")
  .action(function () {
    this.showHelp();
  })
  .command("app", new Command()
    .description("Transfer all installed app directories to a destination directory.")
    .option("--dest <directory:string>", "Destination directory for app sync", { default: "./apk_sync" })
    .option("--timestamp", "Append a timestamp to folder names", { default: false })
    .option("--force", "Force overwrite if directory exists", { default: false })
    .action(async (options) => {
      await syncApps(options.dest, options.timestamp, options.force);
    })
  )
  .parse(Deno.args);
