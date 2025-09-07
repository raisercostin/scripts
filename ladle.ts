#!/usr/bin/env -S deno run --allow-net --allow-read --allow-write --allow-env --allow-run
import { Command } from "https://deno.land/x/cliffy@v1.0.0-rc.4/command/mod.ts";
import { ensureDir } from "https://deno.land/std@0.224.0/fs/ensure_dir.ts";
import { exists } from "https://deno.land/std@0.224.0/fs/exists.ts";
import { join } from "https://deno.land/std@0.224.0/path/mod.ts";

const LADLE_HOME = join(Deno.env.get("HOME") ?? ".", ".ladle");
const APPS_DIR = join(LADLE_HOME, "apps");
const BIN_DIR = join(LADLE_HOME, "bin");
const DEFAULT_BIN_DIR = join(Deno.env.get("HOME") ?? ".", "bin");

let VERBOSITY = 1
let QUIET = 0
let firstTime = true;
function error(msg: string) { log(0, msg); }
function warn(msg: string) { info(msg); }
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

type BucketAppBinary = {
  version?: string;
  url: string;
  type?: "bin";
  bin?: string;
};

type BucketAppSource = {
  version: string;
  url: string;
  type: "src";
  docker: {
    image: string;
    commands: string[];
    output: string;
  };
};

type BucketApp = BucketAppBinary | BucketAppSource;

type BucketManifest = {
  [app: string]: BucketApp;
};

async function listBuckets(): Promise<{ name: string, path: string }[]> {
  info(`Listing buckets from ${LADLE_HOME}`);
  const cfgPath = join(LADLE_HOME, "config.json");
  if (!(await exists(cfgPath))) return [];
  const cfg = JSON.parse(await Deno.readTextFile(cfgPath));
  return Object.entries(cfg.buckets ?? {}).map(([name, path]) => ({ name, path }));
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
  info(`listApps called with full=${opts.full}, verbosity=${opts.verbosity}`);

  const buckets = await loadAllBuckets();
  if (buckets.size === 0) {
    info("listApps: no buckets loaded");
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
  info("listApps completed");
}

async function loadAllBuckets(): Promise<Map<string, BucketManifest>> {
  info(`Loading buckets from config in ${LADLE_HOME}`);
  const buckets = new Map<string, BucketManifest>();
  const entries = await listBuckets();

  if (entries.length === 0) {
    info("No buckets registered");
  }

  for (const { name, path } of entries) {
    info(`Reading bucket '${name}' at ${path}`);

    try {
      const stat = await Deno.stat(path);
      const manifest: BucketManifest = {};

      if (stat.isFile && path.endsWith(".json")) {
        // single-file bucket
        const text = await Deno.readTextFile(path);
        const parsed: BucketManifest = JSON.parse(text);
        Object.assign(manifest, parsed);
        info(`Loaded single-file bucket '${name}' with ${Object.keys(parsed).length} apps`);
      } else if (stat.isDirectory) {
        // directory bucket
        for await (const file of Deno.readDir(path)) {
          if (file.isFile && file.name.endsWith(".json")) {
            const appName = file.name.replace(/\.json$/, "");
            const text = await Deno.readTextFile(join(path, file.name));
            try {
              manifest[appName] = JSON.parse(text);
              info(`Loaded app '${appName}' from bucket '${name}'`);
            } catch (err) {
              info(`Failed to parse ${file.name} in bucket '${name}': ${err}`);
            }
          }
        }
        info(`Loaded directory bucket '${name}' with ${Object.keys(manifest).length} apps`);
      }

      buckets.set(name, manifest);
    } catch (err) {
      info(`Failed to load bucket '${name}': ${err}`);
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
  info(`downloadAndInstall called with url=${url}, dest=${dest}`);
  await ensureDir(DEFAULT_BIN_DIR);

  const resp = await fetch(url);
  if (!resp.ok) {
    if (resp.status === 404) {
      error(`downloadAndInstall: server responded 404 ${resp.statusText} for ${url}`);
      Deno.exit(1);
    }
    throw new Error(`Failed to download from ${url}: ${resp.status} ${resp.statusText}`);
  }

  const tmp = `${dest}.part`;
  const file = await Deno.open(tmp, { write: true, create: true, truncate: true });
  await resp.body?.pipeTo(file.writable);

  await Deno.chmod(tmp, 0o755);
  await Deno.rename(tmp, dest);

  info(`downloadAndInstall: saved to ${dest}`);
}

async function installApp(app: string, opts: { ignoreBuildCache?: boolean; ignoreDownloadCache?: boolean } = {}) {
  info(`installApp called with app='${app}'`);

  const found = await findApp(app);
  if (!found) {
    error(`installApp: app '${app}' not found`);
    console.error(`App '${app}' not found in any bucket.`);
    Deno.exit(1);
  }

  const appInfo = found.info;
  const appName = app.includes("/") ? app.split("/").pop()! : app;
  const version = appInfo.version ?? "unknown";

  const appDir = join(APPS_DIR, appName, version, "bin");
  await ensureDir(appDir);
  const dest = join(appDir, appName);

  if (appInfo.type === "src") {
    info(`installApp: source build requested for '${appName}'`);
    await buildFromSource(appName, appInfo, dest, opts);
  } else {
    info(`installApp: downloading binary from ${appInfo.url} -> ${dest}`);
    await downloadAndInstall(appInfo.url, dest);
  }

  // symlink current version
  const currentLink = join(APPS_DIR, appName, "current");
  try {
    await Deno.remove(currentLink, { recursive: true });
  } catch (_) { }
  await Deno.symlink(join(APPS_DIR, appName, version), currentLink, { type: "dir" });

  // create symlink shim
  await ensureDir(BIN_DIR);
  const binPath = join(BIN_DIR, appName);
  try {
    await Deno.remove(binPath);
  } catch (_) { }
  const shimTarget = join(currentLink, "bin", appName);
  await Deno.symlink(shimTarget, binPath, { type: "file" });

  info(`installApp: completed installation of '${appName}'`);
  console.log(`Installed '${appName}' -> ${binPath} (-> ${shimTarget})`);

  // check if ~/.ladle/bin is in PATH
  const currentPath = Deno.env.get("PATH") ?? "";
  if (!currentPath.split(":").includes(BIN_DIR)) {
    warn(`${BIN_DIR} is not in your PATH.`);
    const home = Deno.env.get("HOME") ?? ".";
    const detected: string[] = [];

    for (const [shell, files] of Object.entries(SHELL_RC_MAP)) {
      const existing: string[] = [];
      for (const rel of files) {
        if (await exists(join(home, rel))) {
          existing.push(rel);
        }
      }
      if (existing.length > 0) {
        // prefer rc files when present
        const recommended = existing.find(f => f.includes("rc"));
        const note = recommended
          ? `recommended (${recommended})` +
          (existing.length > 1
            ? `, other candidates: ${existing.filter(f => f !== recommended).join(", ")}`
            : "")
          : `fallback (${existing.join(", ")})`;
        detected.push(`  ladle init ${shell}   # ${note}`);
      }
    }

    let suggestion = "";
    if (detected.length > 0) {
      suggestion = detected.join("\n");
    } else {
      suggestion =
        `  ladle init sh   # no rc/profile file found, will create ~/.profile`;
    }
    console.error(
      `You won’t be able to run installed tools until you update PATH.\n\n` +
      `Option 1 (manual): add this line to your shell profile:\n\n` +
      `  export PATH="$HOME/.ladle/bin:$PATH"\n\n` +
      `Then restart your shell or run 'source <file>'.\n\n` +
      `Option 2: let Ladle set it up automatically:\n\n${suggestion}\n`
    );
  }
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

async function buildFromSource(
  app: string,
  infoObj: BucketAppSource,
  dest: string,
  opts: { ignoreBuildCache?: boolean; ignoreDownloadCache?: boolean } = {},
) {
  info(`buildFromSource called for '${app}'`);

  const version = infoObj.version;
  const imageTag = `ladle/${app}:${version}`;
  const cacheDir = join(LADLE_HOME, "cache");
  await ensureDir(cacheDir);

  // Download tarball into cache (if referenced in commands)
  const tarName = `${app}#${version}.tar.gz`;
  const tarPath = join(cacheDir, tarName);
  if (opts.ignoreDownloadCache || !(await exists(tarPath))) {
    info(`downloading source tarball into cache: ${tarPath}`);
    const resp = await fetch(infoObj.url);
    if (!resp.ok) throw new Error(`Failed to download source: ${resp.status} ${resp.statusText}`);
    const file = await Deno.open(tarPath, { write: true, create: true, truncate: true });
    await resp.body?.pipeTo(file.writable);
  } else {
    info(`using cached source tarball: ${tarPath}`);
  }

  // Check if we can reuse existing docker image
  let reuseImage = !opts.ignoreBuildCache;
  if (reuseImage) {
    const check = new Deno.Command("docker", {
      args: ["images", "-q", imageTag],
      stdout: "piped",
      stderr: "null",
    });
    const out = await check.output();
    reuseImage = out.stdout.length > 0;
  }

  if (reuseImage) {
    info(`reusing existing docker image ${imageTag}`);
  } else {
    info(`building new docker image ${imageTag}`);
    const runSteps = infoObj.docker.commands
      .map(c => c.replace("{url}", infoObj.url).replace("{version}", version))
      .join(" && ");

    const dockerfile = `
  FROM ${infoObj.docker.image}
  WORKDIR /build
  COPY ${tarName} /build/
  RUN ${runSteps}
  CMD ["cp", "-r", "/out/.", "/out-final/"]
  `;


    const tmpDir = await Deno.makeTempDir();
    await Deno.writeTextFile(join(tmpDir, "Dockerfile"), dockerfile);
    await Deno.copyFile(tarPath, join(tmpDir, tarName));

    const build = new Deno.Command("docker", {
      args: ["build", "-t", imageTag, tmpDir],
      stdout: "inherit",
      stderr: "inherit",
    });
    const status = await build.output();
    if (!status.success) {
      error(`buildFromSource: docker build failed for '${app}'`);
      Deno.exit(1);
    }
  }

  // Run container to copy artifacts from /out to local tmp
  const tmpOut = await Deno.makeTempDir();
  const run = new Deno.Command("docker", {
    args: ["run", "--rm", "-v", `${tmpOut}:/out-final`, imageTag],
    stdout: "inherit",
    stderr: "inherit",
  });
  const runStatus = await run.output();
  if (!runStatus.success) {
    error(`buildFromSource: docker run failed for '${app}'`);
    Deno.exit(1);
  }

  // Copy built binary into Ladle apps dir
  const builtBin = join(tmpOut, infoObj.docker.output.replace("/out/", ""));
  if (!(await exists(builtBin))) {
    error(`Expected binary not found at ${builtBin}`);
    console.error(`Build completed but no binary '${app}' found in /out/bin/`);
    Deno.exit(1);
  }
  info(`copying '${builtBin}' -> '${dest}'`);
  await Deno.copyFile(builtBin, dest);
  await Deno.chmod(dest, 0o755);

  info(`buildFromSource: completed for '${app}'`);
}

const SHELL_RC_MAP: Record<string, string[]> = {
  bash: [".bashrc", ".bash_profile", ".profile"],
  zsh: [".zshrc", ".zprofile"],
  ksh: [".kshrc", ".profile"],
  sh: [".profile"],
  ash: [".profile"],
  dash: [".profile"],
  csh: [".cshrc"],
  tcsh: [".tcshrc"],
  fish: [".config/fish/config.fish"],
};

async function initShell(shell: string) {
  info(`initShell called for '${shell}'`);
  const exportLine = `export PATH="$HOME/.ladle/bin:$PATH"`;
  const home = Deno.env.get("HOME") ?? ".";

  const candidates = SHELL_RC_MAP[shell];
  if (!candidates) {
    error(`Unsupported shell: ${shell}`);
    console.error(`Supported shells: ${Object.keys(SHELL_RC_MAP).join(", ")}`);
    Deno.exit(1);
  }

  // pick first existing rc file, otherwise fallback to first candidate
  let rcFile: string | null = null;
  for (const rel of candidates) {
    if (await exists(join(home, rel))) {
      rcFile = join(home, rel);
      break;
    }
  }
  if (!rcFile) rcFile = join(home, candidates[0]);

  await ensureDir(join(rcFile, ".."));

  let already = false;
  try {
    const contents = await Deno.readTextFile(rcFile);
    if (contents.includes(exportLine)) already = true;
  } catch (_) {
    // file may not exist
  }

  if (already) {
    info(`PATH already configured in ${rcFile}`);
    console.log(`Ladle is already initialized for ${shell} (see ${rcFile})`);
  } else {
    await Deno.writeTextFile(rcFile, `\n# Added by Ladle\n${exportLine}\n`, { append: true });
    info(`Appended PATH export to ${rcFile}`);
    console.log(`Configured Ladle for ${shell}. Added to ${rcFile}:\n  ${exportLine}`);
    console.log(`Run 'source ${rcFile}' or restart your shell to activate.`);
  }
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
  .option("--ignore-build-cache", "Force rebuild from source, ignoring cached Docker image")
  .option("--ignore-download-cache", "Force re-download of source tarball, ignoring cache")
  .action(async (opts, app) => {
    await installApp(app, {
      ignoreBuildCache: opts.ignoreBuildCache,
      ignoreDownloadCache: opts.ignoreDownloadCache,
    });
  })
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
  .command("init <shell:string>", "Configure PATH in shell rc file")
  .action(async (_opts, shell) => {
    await initShell(shell);
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
