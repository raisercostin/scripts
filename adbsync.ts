#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write
import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
//import { Shell } from "https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/desh.ts";
import { Shell } from "./desh.ts";

const theshell = new Shell("info");
const shell = theshell.shell

async function getLocalDirSize(dir: string): Promise<number> {
  let total = 0;
  for await (const entry of Deno.readDir(dir)) {
    const fullPath = `${dir}/${entry.name}`;
    if (entry.isFile) {
      try {
        const info = await Deno.stat(fullPath);
        total += info.size;
      } catch (_) {}
    } else if (entry.isDirectory) {
      total += await getLocalDirSize(fullPath);
    }
  }
  return total;
}

async function getRemoteDirSize(appDir: string): Promise<number> {
  theshell.ignoreError = true
  const lsOutput = await shell`adb shell ls -alR ${appDir}`;
  theshell.ignoreError = false
  let size = 0;
  const lines = lsOutput.split("\n");
  for (const line of lines) {
    const trimmed = line.trim();
    // Skip header lines like "total ..."
    if (trimmed.startsWith("total")) continue;
    // Only process files (lines starting with "-" for regular files)
    if (trimmed.startsWith("-")) {
      const parts = trimmed.split(/\s+/);
      // parts: [permissions, links, user, group, size, ...]
      if (parts.length >= 5) {
        const fileSize = parseInt(parts[4]);
        if (!isNaN(fileSize)) {
          size += fileSize;
        }
      }
    }
  }
  return size;
}

async function syncApps(dest: string, addTimestamp: boolean, forceOverwrite: boolean, rollOlder: boolean, filters: string[]=[]) {
  const appFilters = filters.map(w => w.toLowerCase());
  await Deno.mkdir(dest, { recursive: true });
  const output = await shell`adb shell pm list packages -f`;
  if (!output) {
    console.error("No output from adb. Ensure your device is connected and adb is working.");
    return;
  }
  const lines = output.split("\n").filter(line => line.trim().length > 0);
  const apps: string[] = [];
  for (const line of lines) {
    // Use the last '=' as separator in case the path contains '=' characters.
    const trimmedLine = line.replace(/^package:/, "").trim();
    const lastEqualIndex = trimmedLine.lastIndexOf("=");
    if (lastEqualIndex === -1) continue;
    const fullPath = trimmedLine.substring(0, lastEqualIndex).trim();
    const packageName = trimmedLine.substring(lastEqualIndex + 1).trim();
    apps.push(packageName);
    // Filter by all words in appFilters
    if (appFilters.length > 0 && !appFilters.every(word => packageName.includes(word))) continue;

    // Derive the app directory by removing the filename.
    const appDir = fullPath.substring(0, fullPath.lastIndexOf("/"));
    let localDirName = packageName;
    if (addTimestamp) {
      const now = new Date();
      const timeStamp = now.toISOString().replace(/[:\-\.]/g, "");
      localDirName = `${packageName}_${timeStamp}`;
    }
    const destDir = `${dest}/${localDirName}`;

    let remoteSize = 0;
    try {
      remoteSize = await getRemoteDirSize(appDir);
    } catch (e) {
      console.error(`Error getting remote size for ${appDir}: ${e}`);
      continue;
    }

    let localExists = false;
    let localSize = 0;
    try {
      const stat = await Deno.stat(destDir);
      if (stat.isDirectory) {
        localExists = true;
        localSize = await getLocalDirSize(destDir);
      }
    } catch {
      localExists = false;
    }

    if (localExists) {
      if (localSize === remoteSize) {
        console.log(`Skipping ${packageName} – sizes match (remote: ${remoteSize} bytes, local: ${localSize} bytes).`);
        continue;
      } else {
        console.log(`Pull ${packageName} – size mismatch (remote: ${remoteSize} bytes, local: ${localSize} bytes).`);
        if (rollOlder) {
          let counter = 1;
          let newDir = `${destDir}_old${counter}`;
          while (true) {
            try {
              await Deno.stat(newDir);
              counter++;
              newDir = `${destDir}_${counter}`;
            } catch {
              break;
            }
          }
          await Deno.rename(destDir, newDir);
          console.log(`Renamed existing ${destDir} to ${newDir}`);
        } else if (!forceOverwrite) {
          console.log(`Skipping ${packageName} due to size mismatch (remote: ${remoteSize} bytes, local: ${localSize} bytes) and no force/rollOlder option.`);
          continue;
        }
      }
    }

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
  .description("Sync installed app directories from a OnePlus6T using adb based on ls -alR sizes.")
  .action(function () {
    this.showHelp();
  })
  .command("app", new Command()
    .description("Transfer all installed app directories to a destination if remote size (via ls -alR) differs from local backup.")
    .option("--dest <directory:string>", "Destination directory for app sync", { default: "./apk_sync" })
    .option("--timestamp", "Append a timestamp to folder names", { default: false })
    .option("--force [force:boolean]", "Force overwrite if directory exists and sizes differ", { default: false })
    .option("--rollOlder", "Rename existing local directory if sizes differ (append a counter)", { default: true })
    .option("--filter <word:string>", "Only sync apps containing this word", { collect: true })
    .action(async (options) => {
      console.log("options are:", options);
      await syncApps(options.dest, options.timestamp, options.force, options.rollOlder, options.filter ?? []);
    })
  )
  .parse(Deno.args);