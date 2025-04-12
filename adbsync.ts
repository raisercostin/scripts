#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write
import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { Shell } from "https://raw.githubusercontent.com/raisercostin/scripts-ts/refs/heads/main/desh.ts";

const { shell } = new Shell("info");

async function syncApks(dest: string, addTimestamp: boolean, forceOverwrite: boolean) {
  await Deno.mkdir(dest, { recursive: true });
  const output = await shell`adb shell pm list packages -f`;
  if (!output) {
    console.error("No output from adb. Ensure your device is connected and adb is working.");
    return;
  }
  const lines = output.split("\n").filter(line => line.trim().length > 0);
  for (const line of lines) {
    const [apkPathRaw, packageNameRaw] = line.replace(/^package:/, "").split("=");
    if (!apkPathRaw || !packageNameRaw) continue;
    const apkPath = apkPathRaw.trim();
    const packageName = packageNameRaw.trim();
    let fileName = `${packageName}.apk`;
    if (addTimestamp) {
      const now = new Date();
      const timeStamp = now.toISOString().replace(/[:\-\.]/g, "");
      fileName = `${packageName}_${timeStamp}.apk`;
    }
    const destFile = `${dest}/${fileName}`;
    let fileExists = false;
    try {
      await Deno.stat(destFile);
      fileExists = true;
    } catch (error) {
      if (!(error instanceof Deno.errors.NotFound)) {
        console.error(`Error checking file ${destFile}: ${error}`);
        continue;
      }
    }
    if (fileExists && !forceOverwrite) {
      console.log(`Skipping ${destFile} (already exists; use --force to overwrite)`);
      continue;
    }
    console.log(`Pulling APK for ${packageName} from ${apkPath} to ${destFile} ...`);
    try {
      await shell`adb pull ${apkPath} ${destFile}`;
    } catch (error) {
      console.error(`Failed to pull ${apkPath} for ${packageName}: ${error}`);
    }
  }
  console.log("Sync complete.");
}

await new Command()
  .name("adbsync")
  .version("0.1")
  .description("Sync all installed APKs from a OnePlus6T using adb.")
  .action(function () {
    this.showHelp();
  })
  .command("app", new Command()
    .description("Transfer all installed APKs to a destination directory.")
    .option("--dest <directory:string>", "Destination directory for APK sync", { default: "./apk_sync" })
    .option("--timestamp", "Append a timestamp to filenames", { default: false })
    .option("--force", "Force overwrite if file exists", { default: false })
    .action(async (options) => {
      await syncApks(options.dest, options.timestamp, options.force);
    })
  )
  .parse(Deno.args);
