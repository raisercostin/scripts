#!/usr/bin/env -S deno run --allow-read

import { Command } from "https://deno.land/x/cliffy@v1.0.0-rc.4/command/mod.ts";
import { resolve } from "https://deno.land/std@0.224.0/path/mod.ts";

interface FilematcherOptions {
  file: string;
  to: "rsync";
}

function convertToRsync(options: FilematcherOptions): void {
  let content: string;

  try {
    content = Deno.readTextFileSync(resolve(options.file));
  } catch (err) {
    console.error(`❌ Failed to read file: ${options.file}`);
    console.error(err.message);
    Deno.exit(1);
  }

  const includes: string[] = [];
  const excludes: string[] = [];

  content.split(/\r?\n/).forEach((raw, index) => {
    const line = raw.trim();
    if (!line || line.startsWith("#")) return;

    if (line.startsWith("!")) {
      // (use the improved block from previous answer)
      const path = line.slice(1).replace(/^\/+/, "");
      if (path) {
        try {
          if (Deno.statSync(path).isDirectory) {
            includes.push(`--include=${path}/`);
            includes.push(`--include=${path}/***`);
          } else {
            includes.push(`--include=${path}`);
          }
        } catch {
          includes.push(`--include=${path}`);
          includes.push(`--include=${path}/`);
          includes.push(`--include=${path}/***`);
        }
      } else {
        console.warn(`⚠️  Line ${index + 1}: Skipped empty negation`);
      }
    } else {
      const path = line.replace(/^\/+/, "");
      if (path.endsWith("/")) {
        excludes.push(`--exclude=${path}`);
        excludes.push(`--exclude=${path}***`);
      } else {
        excludes.push(`--exclude=${path}`);
      }
    }
  });
  ["--include=/", ...includes, ...excludes, "--exclude=*"].forEach((line) => console.log(line));

  if (includes.length === 0) {
    console.warn("💡 Clippy says: No `!` include rules found — rsync may exclude everything.");
  } else {
    console.error("✅ Clippy: Rules translated for rsync. Happy syncing!");
  }
}

await new Command()
  .name("filematcher")
  .version("0.1.0")
  .description("Convert ignore/include files into format-specific filters")
  .arguments("<file:string>")
  .option("--to <target:string>", "Output format (currently only 'rsync')", {
    required: true,
    values: ["rsync"],
  })
  .action((options, file) => {
    const typed: FilematcherOptions = {
      file,
      to: options.to as "rsync",
    };
    convertToRsync(typed);
  })
  .parse(Deno.args);
