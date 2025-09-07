// ladle.ts
import { Command } from "https://deno.land/x/cliffy@v1.0.0-rc.4/command/mod.ts";
import { ensureDir } from "https://deno.land/std@0.224.0/fs/ensure_dir.ts";
import { exists } from "https://deno.land/std@0.224.0/fs/exists.ts";
import { join } from "https://deno.land/std@0.224.0/path/mod.ts";

const BUCKETS_DIR = join(Deno.cwd(), "buckets");
const DEFAULT_BIN_DIR = `${Deno.env.get("HOME")}/bin`;

type BucketManifest = {
  [app: string]: {
    url: string;
    version?: string;
    bin?: string; // optional path inside archive
  }
};

async function listBuckets(): Promise<string[]> {
  if (!(await exists(BUCKETS_DIR))) return [];
  return Array.from(Deno.readDirSync(BUCKETS_DIR))
    .filter(f => f.isFile && f.name.endsWith(".json"))
    .map(f => f.name.replace(/\.json$/, ""));
}

async function loadAllBuckets(): Promise<Map<string, BucketManifest>> {
  const buckets = new Map<string, BucketManifest>();
  for await (const entry of await listBuckets()) {
    const path = join(BUCKETS_DIR, `${entry}.json`);
    const text = await Deno.readTextFile(path);
    buckets.set(entry, JSON.parse(text));
  }
  return buckets;
}

async function findApp(app: string): Promise<{ bucket: string, info: any } | null> {
  const buckets = await loadAllBuckets();
  for (const [bucket, manifest] of buckets) {
    if (manifest[app]) return { bucket, info: manifest[app] };
  }
  return null;
}

async function downloadAndInstall(url: string, dest: string) {
  await ensureDir(DEFAULT_BIN_DIR);
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`Failed to download: ${resp.statusText}`);
  const file = await Deno.open(dest, { write: true, create: true, truncate: true });
  await resp.body?.pipeTo(file.writable);
  await Deno.chmod(dest, 0o755);
}

async function installApp(app: string) {
  const found = await findApp(app);
  if (!found) {
    console.error(`App '${app}' not found in any bucket.`);
    Deno.exit(1);
  }
  const { url } = found.info;
  const dest = join(DEFAULT_BIN_DIR, app);
  await downloadAndInstall(url, dest);
  console.log(`Installed '${app}' to ${dest}`);
}

async function addBucket(url: string, name?: string) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch bucket: ${res.statusText}`);
  await ensureDir(BUCKETS_DIR);
  const bucketName = name || url.split("/").pop()?.replace(/\.json$/, "") || "bucket";
  const path = join(BUCKETS_DIR, `${bucketName}.json`);
  await Deno.writeTextFile(path, await res.text());
  console.log(`Added bucket '${bucketName}' at ${path}`);
}

// CLI setup (SRP: only wiring, not business logic)
await new Command()
  .name("ladle")
  .version("0.1.0")
  .description("User-local binary installer inspired by scoop")
  .command("install <app:string>", "Install an app from all buckets")
    .action(async (_opts, app) => { await installApp(app); })
  .command("bucket add <url:string> [name:string]", "Add a bucket manifest from url")
    .action(async (_opts, url, name) => { await addBucket(url, name); })
  .command("bucket list", "List available buckets")
    .action(async () => {
      const buckets = await listBuckets();
      for (const b of buckets) console.log(b);
    })
  .command("list", "List all apps in all buckets")
    .action(async () => {
      const buckets = await loadAllBuckets();
      for (const [bucket, manifest] of buckets) {
        for (const app of Object.keys(manifest)) {
          console.log(`${app} (${bucket})`);
        }
      }
    })
  .parse(Deno.args);
