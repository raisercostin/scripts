#!/usr/bin/env -S deno run --allow-net --allow-read --allow-write --allow-env --allow-run
import { Command } from "https://deno.land/x/cliffy@v1.0.0-rc.4/command/mod.ts";
import { ensureDir } from "https://deno.land/std@0.224.0/fs/ensure_dir.ts";
import { exists } from "https://deno.land/std@0.224.0/fs/exists.ts";
import { join } from "https://deno.land/std@0.224.0/path/mod.ts";

const LADLE_HOME = join(Deno.env.get("HOME") ?? ".", ".ladle");
const BUCKETS_DIR = join(LADLE_HOME, "buckets");
const DEFAULT_BIN_DIR = join(Deno.env.get("HOME") ?? ".", "bin");

let VERBOSITY = 1
let QUIET = 0
let firstTime = true;
function error(msg: string) { log(0, msg); }
function warn(msg: string) { log(1, msg); }
function info(msg: string) { log(2, msg); }
function debug(msg: string) { log(3, msg); }
function trace(msg: string) { log(4, msg); }
function log(level: number, msg: string) {
  if (firstTime) {
    firstTime = false;
    info(`Ladle home directory: ${LADLE_HOME}`);
    info(`Ladle default bin directory: ${DEFAULT_BIN_DIR}`);
    debug(`Ladle verbosity level: ${VERBOSITY - QUIET}`);
  }
  if (level <= VERBOSITY - QUIET) {
    const prefix = ["ERROR", "WARN", "INFO", "DEBUG", "TRACE", "TRACE5"][level] ?? "LOG";
    console.error(`[${prefix}] ${msg}`);
  }
}

type BucketManifest = {
  [app: string]: {
    url: string;
    version?: string;
    bin?: string; // optional path inside archive
  }
};

async function listBuckets(): Promise<{ name: string, path: string }[]> {
  info( `Listing buckets from ${LADLE_HOME}`);
  const cfgPath = join(LADLE_HOME, "config.json");
  if (!(await exists(cfgPath))) return [];
  const cfg = JSON.parse(await Deno.readTextFile(cfgPath));
  return Object.entries(cfg.buckets ?? {}).map(([name, path]) => ({ name, path }));
}

async function loadAllBuckets(): Promise<Map<string, BucketManifest>> {
  info( `Loading buckets from config in ${LADLE_HOME}`);
  const buckets = new Map<string, BucketManifest>();
  const entries = await listBuckets();

  if (entries.length === 0) {
    info( "No buckets registered");
  }

  for (const { name, path } of entries) {
    info( `Reading bucket '${name}' at ${path}`);

    try {
      const stat = await Deno.stat(path);
      const manifest: BucketManifest = {};

      if (stat.isFile && path.endsWith(".json")) {
        // single-file bucket
        const text = await Deno.readTextFile(path);
        const parsed: BucketManifest = JSON.parse(text);
        Object.assign(manifest, parsed);
        info( `Loaded single-file bucket '${name}' with ${Object.keys(parsed).length} apps`);
      } else if (stat.isDirectory) {
        // directory bucket
        for await (const file of Deno.readDir(path)) {
          if (file.isFile && file.name.endsWith(".json")) {
            const appName = file.name.replace(/\.json$/, "");
            const text = await Deno.readTextFile(join(path, file.name));
            try {
              manifest[appName] = JSON.parse(text);
              info( `Loaded app '${appName}' from bucket '${name}'`);
            } catch (err) {
              info( `Failed to parse ${file.name} in bucket '${name}': ${err}`);
            }
          }
        }
        info( `Loaded directory bucket '${name}' with ${Object.keys(manifest).length} apps`);
      }

      buckets.set(name, manifest);
    } catch (err) {
      info( `Failed to load bucket '${name}': ${err}`);
    }
  }

  return buckets;
}


async function findApp(app: string): Promise<{ bucket: string; info: any } | null> {
  const buckets = await loadAllBuckets();

  // case: user specified bucket/app
  if (app.includes("/")) {
    const [bucketName, appName] = app.split("/", 2);
    const manifest = buckets.get(bucketName);
    if (manifest && manifest[appName]) {
      return { bucket: bucketName, info: manifest[appName] };
    }
    return null;
  }

  // case: search across all buckets
  for (const [bucket, manifest] of buckets) {
    if (manifest[app]) return { bucket, info: manifest[app] };
  }

  return null;
}

async function downloadAndInstall(url: string, dest: string) {
  info( `downloadAndInstall called with url=${url}, dest=${dest}`);
  await ensureDir(DEFAULT_BIN_DIR);

  const resp = await fetch(url);
  if (!resp.ok) {
    if (resp.status === 404) {
      error( `downloadAndInstall: server responded 404 ${resp.statusText} for ${url}`);
      Deno.exit(1);
    }
    throw new Error(`Failed to download from ${url}: ${resp.status} ${resp.statusText}`);
  }

  const tmp = `${dest}.part`;
  const file = await Deno.open(tmp, { write: true, create: true, truncate: true });
  await resp.body?.pipeTo(file.writable);

  await Deno.chmod(tmp, 0o755);
  await Deno.rename(tmp, dest);

  info( `downloadAndInstall: saved to ${dest}`);
}

async function installApp(app: string) {
  info( `installApp called with app='${app}'`);

  const found = await findApp(app);
  if (!found) {
    info( `installApp: no match for '${app}'`);
    console.error(`App '${app}' not found in any bucket.`);
    Deno.exit(1);
  }

  const { url } = found.info;
  const dest = join(DEFAULT_BIN_DIR, app.includes("/") ? app.split("/").pop()! : app);

  info( `installApp: downloading from ${url} -> ${dest}`);
  await downloadAndInstall(url, dest);

  info( `installApp: completed installation of '${app}'`);
  console.log(`Installed '${app}' to ${dest}`);
}

async function uninstallApp(app: string) {
  const dest = join(DEFAULT_BIN_DIR, app);
  if (await exists(dest)) {
    await Deno.remove(dest);
    console.log(`Uninstalled '${app}' from ${dest}`);
  } else {
    console.error(`'${app}' is not installed.`);
  }
}

async function addBucket(url: string, name?: string) {
  await ensureDir(LADLE_HOME);
  const cfgPath = join(LADLE_HOME, "config.json");
  let cfg = { buckets: {} as Record<string, string> };
  if (await exists(cfgPath)) {
    cfg = JSON.parse(await Deno.readTextFile(cfgPath));
  }

  const bucketName = name || url.split("/").pop()?.replace(/\.json$/, "") || "bucket";
  const absPath = url.startsWith("http://") || url.startsWith("https://")
    ? url
    : join(Deno.cwd(), url);

  cfg.buckets[bucketName] = absPath;
  await Deno.writeTextFile(cfgPath, JSON.stringify(cfg, null, 2));
  console.log(`Added bucket '${bucketName}' -> ${absPath}`);
}

async function listApps(opts: ListOptions) {
  info( `listApps called with full=${opts.full}, verbosity=${opts.verbosity}`);

  const buckets = await loadAllBuckets();
  if (buckets.size === 0) {
    info( "listApps: no buckets loaded");
    console.log("No apps available");
    return;
  }

  // cache entries once
  const entries = await listBuckets();

  for (const [bucketName, manifest] of buckets) {
    for (const app of Object.keys(manifest)) {
      if (opts.full) {
        const bucketEntry = entries.find(e => e.name === bucketName);
        const base = bucketEntry ? bucketEntry.path : bucketName;
        console.log(`${base}/${app}`);
      } else {
        console.log(`${bucketName}/${app}`);
      }
    }
  }

  info( "listApps completed");
}

interface ListOptions {
  full: boolean;
  verbosity: number;
}

await new Command()
  .name("ladle")
  .version("0.1.0")
  .description("Scoop like installer for Linux - user space, buckets, user light contributions")
  .action(function () { this.showHelp(); })
  .globalOption("-v, --verbose", "Increase verbosity", { collect: true, value: () => { VERBOSITY++; return VERBOSITY; } })
  .globalOption("-q, --quiet", "Decrease verbosity", { collect: true, value: () => { QUIET++; return QUIET; } })
  .command("install <app:string>", "Install an app from all buckets")
  .action(async (_opts, app) => { await installApp(app); })
  .command("uninstall <app:string>", "Uninstall an app")
  .action(async (_opts, app) => { await uninstallApp(app); })
  .command("bucket", new Command()
    .description("Manage buckets")
    .command("add <url:string> [name:string]", "Add a bucket manifest from url")
    .action(async (_opts, url, name) => { await addBucket(url, name); })
    .command("list", "List available buckets")
    .action(async () => {
      const buckets = await listBuckets();
      for (const b of buckets) console.log(b);
    })
  )
  .command("list", "List all apps in all buckets")
  .option("--full", "Show full bucket path")
  .action(async (cliOpts) => {
    await listApps({
      full: cliOpts.full ?? false,
      verbosity: VERBOSITY,
    });
  })
  .command("system-info", "Show system architecture and distribution")
  .action(async () => {
    console.log("Platform:", Deno.build.os);
    console.log("Arch:", Deno.build.arch);

    try {
      const uname = new Deno.Command("uname", { args: ["-a"] });
      const { stdout } = await uname.output();
      console.log("Uname:", new TextDecoder().decode(stdout).trim());
    } catch {
      console.log("Uname: not available");
    }
  })
  .parse(Deno.args);
