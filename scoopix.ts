#!/usr/bin/env -S deno run --allow-net --allow-read --allow-write --allow-env --allow-run
import { ensureDir } from "https://deno.land/std@0.224.0/fs/ensure_dir.ts";
import { exists } from "https://deno.land/std@0.224.0/fs/exists.ts";
import { join, dirname, basename } from "https://deno.land/std@0.224.0/path/mod.ts";
import { Command } from "https://deno.land/x/cliffy@v1.0.0-rc.4/command/mod.ts";

const SCOOPIX_HOME = join(Deno.env.get("HOME") ?? ".", ".scoopix");
const APPS_DIR = join(SCOOPIX_HOME, "apps");
const BIN_DIR = join(SCOOPIX_HOME, "bin");
const DEFAULT_BIN_DIR = join(Deno.env.get("HOME") ?? ".", "bin");
const CACHE_DIR = join(SCOOPIX_HOME, "cache");
const TEMP_DIR = join(SCOOPIX_HOME, "temp");

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
    info(`Scoopix home directory: ${SCOOPIX_HOME}`);
    info(`Scoopix default bin directory: ${DEFAULT_BIN_DIR}`);
    debug(`Scoopix verbosity level: ${VERBOSITY - QUIET}`);
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

export type ScoopixBinary = string | string[];

export interface ScoopixArchEntry {
  url: string;
  extract?: "zip" | "tar.gz" | "tgz";
  bin: ScoopixBinary;
  man?: string; // optional path to a man page inside archive
}
export interface ScoopixDocker {
  image: string;
  commands: string[];
  output: string;
}

export interface ScoopixApp {
  version: string;
  description?: string;
  homepage?: string;
  license?: string;
  type?: "bin" | "src";
  url?: string;
  extract?: "zip" | "tar.gz" | "tgz";
  bin?: ScoopixBinary;
  arch?: Record<string, ScoopixArchEntry>;
  docker?: ScoopixDocker;
}

export type ScoopixManifest = Record<string, ScoopixApp>;

async function listBuckets(): Promise<{ name: string, path: string }[]> {
  info(`Listing buckets from ${SCOOPIX_HOME}`);
  const cfgPath = join(SCOOPIX_HOME, "config.json");
  if (!(await exists(cfgPath))) return [];
  const cfg = JSON.parse(await Deno.readTextFile(cfgPath));
  return Object.entries(cfg.buckets ?? {}).map(([name, path]) => ({ name, path }));
}

async function addBucket(url: string, name?: string) {
  await ensureDir(SCOOPIX_HOME);
  const cfgPath = join(SCOOPIX_HOME, "config.json");
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

async function listApps(full: boolean) {
  info(`listApps called with full=${full}`);

  const buckets = await loadAllBuckets();
  if (buckets.size === 0) {
    info("listApps: no buckets loaded");
    console.log("No apps available");
    return;
  }
  for (const [bucket, manifest] of buckets) {
    for (const [app, meta] of Object.entries(manifest)) {
      const appName = full ? `${bucket}/${app}` : `${bucket}/${app}`;
      const description = meta.description ?? "";
      if (description) {
        console.log(`${appName} - ${description}`);
      } else {
        console.log(appName);
      }
    }
  }

  info("listApps completed");
}

async function loadAllBuckets(): Promise<Map<string, BucketManifest>> {
  info(`Loading buckets from config in ${SCOOPIX_HOME}`);
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
async function downloadAndInstall(
  url: string,
  dest: string,
  extract?: "zip" | "tar.gz" | "tgz",
  bin?: string,
  opts: { keepTemp?: boolean; man?: string; appName?: string } = {}
) {
  const appName = opts.appName ?? basename(dest);
  const versionPart = dest.split("/").slice(-3, -2)[0];
  const ext = extract === "zip" ? "zip" : "tar.gz";
  const cacheFile = join(CACHE_DIR, `${appName}#${versionPart}.${ext}`);
  const tempDir = join(TEMP_DIR, appName);

  info(`downloadAndInstall: preparing cache at ${cacheFile}`);
  await ensureDir(CACHE_DIR);
  await ensureDir(TEMP_DIR);

  // download if not cached
  try {
    await Deno.stat(cacheFile);
    info(`downloadAndInstall: using cached file ${cacheFile}`);
  } catch {
    info(`downloadAndInstall: downloading ${url} -> ${cacheFile}`);
    const resp = await fetch(url);
    if (!resp.ok) {
      throw new Error(`Failed to download: ${resp.status} ${resp.statusText}`);
    }
    const file = await Deno.open(cacheFile, {
      write: true,
      create: true,
      truncate: true,
    });
    await resp.body?.pipeTo(file.writable);
  }

  if (extract) {
    info(`downloadAndInstall: extracting ${extract} archive into ${tempDir}`);
    await Deno.remove(tempDir, { recursive: true }).catch(() => { });
    await ensureDir(tempDir);

    if (extract === "tar.gz" || extract === "tgz") {
      const cmd = new Deno.Command("tar", {
        args: ["-xzf", cacheFile, "-C", tempDir],
      });
      const { code } = await cmd.output();
      if (code !== 0) throw new Error(`tar extraction failed for ${url}`);
    } else if (extract === "zip") {
      const cmd = new Deno.Command("unzip", {
        args: ["-o", cacheFile, "-d", tempDir],
      });
      const { code } = await cmd.output();
      if (code !== 0) throw new Error(`unzip extraction failed for ${url}`);
    }

    if (!bin) throw new Error(`Archive from ${url} requires a 'bin' field`);
    const src = join(tempDir, bin);
    await ensureDir(dirname(dest));
    await Deno.copyFile(src, dest);

    if (opts.man) {
      const manTargetDir = join(SCOOPIX_HOME, "share", "man", "man1");
      await ensureDir(manTargetDir);
      const manDest = join(manTargetDir, `${appName}.1`);
      await Deno.copyFile(join(tempDir, opts.man), manDest);
      info(`downloadAndInstall: installed man page -> ${manDest}`);
    }

    if (!opts.keepTemp) {
      await Deno.remove(tempDir, { recursive: true }).catch(() => { });
      info(`downloadAndInstall: cleaned temp dir ${tempDir}`);
    } else {
      warn(`downloadAndInstall: kept temp dir for debugging: ${tempDir}`);
    }
  } else {
    info(`downloadAndInstall: copying cached binary ${cacheFile} -> ${dest}`);
    await ensureDir(dirname(dest));
    await Deno.copyFile(cacheFile, dest);
  }

  await Deno.chmod(dest, 0o755);
  info(`downloadAndInstall: saved to ${dest}`);
}
async function installApp(
  app: string,
  opts: { ignoreBuildCache?: boolean; ignoreDownloadCache?: boolean; keepTemp?: boolean } = {}
) {
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

  const arch = await detectArch();
  info(`installApp: detected architecture '${arch}'`);

  // Merge parent-level and arch-specific fields
  const archObj = appInfo.arch?.[arch];
  const infoObj = { ...appInfo, ...(archObj ?? {}) };

  if (appInfo.type !== "src" && !infoObj.url) {
    error(`installApp: no URL found for '${appName}' (arch=${arch})`);
    Deno.exit(1);
  }

  const appDir = join(APPS_DIR, appName, version, "bin");
  await ensureDir(appDir);

  // Use manifest’s `bin` path if defined, otherwise default to appName
  const binName = infoObj.bin ?? appName;
  const dest = join(appDir, binName);

  if (infoObj.type === "src") {
    info(`installApp: source build requested for '${appName}'`);
    await buildFromSource(appName, infoObj, dest, opts);
  } else {
    info(`installApp: downloading binary from ${infoObj.url} -> ${dest}`);
    await downloadAndInstall(
      infoObj.url!,
      dest,
      infoObj.extract,
      infoObj.bin,
      { keepTemp: opts.keepTemp, man: infoObj.man, appName }
    );
  }

  // Symlink "current"
  const currentLink = join(APPS_DIR, appName, "current");
  try {
    await Deno.remove(currentLink, { recursive: true });
  } catch {}
  await Deno.symlink(join(APPS_DIR, appName, version), currentLink, { type: "dir" });

  // Symlink into ~/.scoopix/bin
  await ensureDir(BIN_DIR);
  const binPath = join(BIN_DIR, appName);
  try {
    await Deno.remove(binPath);
  } catch {}
  const shimTarget = join(currentLink, "bin", binName);
  await Deno.symlink(shimTarget, binPath, { type: "file" });

  info(`installApp: completed installation of '${appName}'`);
  console.log(`Installed '${appName}' -> ${binPath} (-> ${shimTarget})`);

  // PATH check
  const currentPath = Deno.env.get("PATH") ?? "";
  if (!currentPath.split(":").includes(BIN_DIR)) {
    const suggestions = await detectShellInits();
    const lines = formatShellInits(suggestions);
    warn(`${BIN_DIR} is not in your PATH.`);
    console.error(
      `You won’t be able to run installed tools until you update PATH.\n\n` +
      `Option 1 (manual): add this line to your shell profile:\n\n` +
      `  export PATH="$HOME/.scoopix/bin:$PATH"\n\n` +
      `Then restart your shell or run 'source <file>'.\n\n` +
      `Option 2: let Scoopix set it up automatically:\n\n${lines.join("\n")}\n`
    );
  }

  // MANPATH check
  const currentManpath = Deno.env.get("MANPATH") ?? "";
  const scoopixMan = join(SCOOPIX_HOME, "share", "man");
  if (!currentManpath.split(":").includes(scoopixMan)) {
    warn(`${scoopixMan} is not in your MANPATH.`);
    console.error(
      `To use 'man <app>', add this line to your shell profile:\n\n` +
      `  export MANPATH="$HOME/.scoopix/share/man:$MANPATH"\n`
    );
  }
}

async function detectArch(): Promise<string> {
  const sysArch = Deno.build.arch; // coarse value
  try {
    const p = Deno.run({ cmd: ["uname", "-m"], stdout: "piped" });
    const raw = new TextDecoder().decode(await p.output()).trim();
    await p.status();
    switch (raw) {
      case "x86_64":
      case "amd64":
        return "x86_64";
      case "aarch64":
      case "arm64":
        return "aarch64";
      case "armv7l":
      case "armhf":
      case "arm":
        return "armv7";
      default:
        return sysArch; // fallback to Deno's
    }
  } catch {
    return sysArch;
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

  const version = infoObj.version ?? "unknown";
  const imageTag = `scoopix/${app}:${version}`;
  const cacheDir = join(SCOOPIX_HOME, "cache");
  await ensureDir(cacheDir);

  // Optional tarball caching (only if url is present)
  let tarName: string | undefined;
  let tarPath: string | undefined;
  if (infoObj.url) {
    tarName = `${app}#${version}.tar.gz`;
    tarPath = join(cacheDir, tarName);
    if (opts.ignoreDownloadCache || !(await exists(tarPath))) {
      info(`downloading source tarball into cache: ${tarPath}`);
      const resp = await fetch(infoObj.url);
      if (!resp.ok) {
        throw new Error(`Failed to download source: ${resp.status} ${resp.statusText}`);
      }
      const file = await Deno.open(tarPath, { write: true, create: true, truncate: true });
      await resp.body?.pipeTo(file.writable);
    } else {
      info(`using cached source tarball: ${tarPath}`);
    }
  }

  // Check if we can reuse docker image
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
      .map(c =>
        c
          .replace("{url}", infoObj.url ?? "")
          .replace("{version}", version)
          .replace("{app}", app)
      )
      .join(" && ");

    const dockerfile = [
      `FROM ${infoObj.docker.image}`,
      `WORKDIR /build`,
      tarName ? `COPY ${tarName} /build/` : "",
      `RUN ${runSteps}`,
      `CMD ["cp", "-r", "/out/.", "/out-final/"]`,
    ].filter(Boolean).join("\n");

    const tmpDir = await Deno.makeTempDir();
    await Deno.writeTextFile(join(tmpDir, "Dockerfile"), dockerfile);
    if (tarPath) {
      await Deno.copyFile(tarPath, join(tmpDir, tarName!));
    }

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

  // Copy built binary into Scoopix apps dir
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
type ShellInitSuggestion = {
  shell: string;
  recommended: string;
  alternates: string[];
};

async function detectShellInits(): Promise<ShellInitSuggestion[]> {
  const home = Deno.env.get("HOME") ?? ".";
  const results: ShellInitSuggestion[] = [];

  for (const [shell, files] of Object.entries(SHELL_RC_MAP)) {
    const existing: string[] = [];
    for (const rel of files) {
      if (await exists(join(home, rel))) {
        existing.push(rel);
      }
    }
    if (existing.length > 0) {
      const recommended = existing.find(f => f.includes("rc")) ?? existing[0];
      const alternates = existing.filter(f => f !== recommended);
      results.push({ shell, recommended, alternates });
    }
  }

  if (results.length === 0) {
    results.push({ shell: "sh", recommended: ".profile", alternates: [] });
  }

  return results;
}
function formatShellInits(suggestions: ShellInitSuggestion[]): string[] {
  return suggestions.map(s => {
    const note = s.alternates.length > 0
      ? `recommended (${s.recommended}), other candidates: ${s.alternates.join(", ")}`
      : `recommended (${s.recommended})`;
    return `  scoopix init ${s.shell}   # ${note}`;
  });
}
async function initShell(shellArg?: string) {
  let shell = shellArg;
  if (!shell) {
    // fallback to current shell from $SHELL
    const envShell = Deno.env.get("SHELL");
    if (envShell) {
      shell = envShell.split("/").pop() ?? "sh";
      info(`initShell: auto-detected current shell as '${shell}' from SHELL=${envShell}`);
    } else {
      shell = "sh"; // final fallback
      warn("initShell: could not detect current shell, defaulting to 'sh'");
    }
  } else {
    info(`initShell: shell argument provided: '${shell}'`);
  }

  const exportLine = `export PATH="$HOME/.scoopix/bin:$PATH"\nexport MANPATH="$HOME/.scoopix/share/man:$MANPATH"`;
  const home = Deno.env.get("HOME") ?? ".";
  const suggestions = await detectShellInits();
  const found = suggestions.find(s => s.shell === shell);

  if (!found) {
    error(`Unsupported or undetected shell: ${shell}`);
    console.error(`Supported shells: ${Object.keys(SHELL_RC_MAP).join(", ")}`);
    Deno.exit(1);
  }

  const rcFile = join(home, found.recommended);

  await ensureDir(join(rcFile, ".."));

  let already = false;
  try {
    const contents = await Deno.readTextFile(rcFile);
    if (contents.includes(exportLine)) already = true;
  } catch {
    // file may not exist yet
  }

  if (already) {
    info(`PATH already configured in ${rcFile}`);
    console.log(`Scoopix is already initialized for ${shell} (see ${rcFile})`);
  } else {
    await Deno.writeTextFile(rcFile, `\n# Added by Scoopix\n${exportLine}\n`, { append: true });
    info(`Appended PATH export to ${rcFile}`);
    console.log(`Configured Scoopix for ${shell}. Modified ${rcFile}:\n  ${exportLine}`);
    if (found.alternates.length > 0) {
      console.log(`Note: other candidate files also exist: ${found.alternates.join(", ")}`);
    }
    console.log(`Run 'source ${rcFile}' or restart your shell to activate.`);
  }
}

await new Command()
  .name("scoopix")
  .version("0.1.0")
  .description("Scoop like installer for Linux - user space, buckets, user light contributions")
  .action(function () { this.showHelp(); })
  .globalOption("-v, --verbose", "Increase verbosity", { collect: true, value: () => { VERBOSITY++; return VERBOSITY; } })
  .globalOption("-q, --quiet", "Decrease verbosity", { collect: true, value: () => { QUIET++; return QUIET; } })
  .command("install <app:string>", "Install an app from all buckets")
  .option("--ignore-build-cache", "Force rebuild from source, ignoring cached Docker image")
  .option("--ignore-download-cache", "Force re-download even if cached")
  .option("--keep-temp", "Keep extracted files in ~/.scoopix/temp/<app>")
  .action(async (opts, app) => {
    await installApp(app, {
      ignoreBuildCache: opts.ignoreBuildCache,
      ignoreDownloadCache: opts.ignoreDownloadCache,
      keepTemp: opts.keepTemp,
    });
  })
  .command("uninstall <app:string>", "Uninstall an app")
  .action(async (_opts, app) => { await uninstallApp(app); })
  .command("bucket", new Command()
    .description("Manage buckets")
    .action(function () { this.showHelp(); })
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
    await listApps(cliOpts.full ?? false);
  })
  .command("init [shell:string]", "Configure PATH in shell rc file")
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
