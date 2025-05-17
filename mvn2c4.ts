#!/usr/bin/env deno run --allow-read
import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { join, normalize, sep } from "https://deno.land/std@0.160.0/path/mod.ts";
import { DOMParser } from "https://deno.land/x/deno_dom/deno-dom-wasm.ts";

async function extractDeps(
  workdir: string = ".",
  groupIds: string[],
  maxDepth: number = 2,
) {
  // normalize base path and compute its depth
  workdir = normalize(workdir);
  const baseDepth = workdir.split(sep).length;

  // recursive search for pom.xml up to maxDepth
  async function findPomFiles(dir: string, currentDepth: number): Promise<string[]> {
    if (currentDepth > maxDepth) return [];
    const results: string[] = [];
    for await (const entry of Deno.readDir(dir)) {
      const fullPath = join(dir, entry.name);
      if (entry.isFile && entry.name === "pom.xml") {
        results.push(fullPath);
      } else if (entry.isDirectory) {
        results.push(...await findPomFiles(fullPath, currentDepth + 1));
      }
    }
    return results;
  }

  const pomFiles = await findPomFiles(workdir, 0);

  // collect dependencies per groupId
  const deps: Record<string, Set<string>> = {};
  for (const gid of groupIds) deps[gid] = new Set();

  for (const path of pomFiles) {
    const xml = await Deno.readTextFile(path);
    const doc = new DOMParser().parseFromString(xml, "text/html");
    if (!doc) continue;
    for (const depEl of doc.querySelectorAll("dependency")) {
      const gidEl = depEl.querySelector("groupId");
      const aidEl = depEl.querySelector("artifactId");
      if (!gidEl || !aidEl) continue;
      const gid = gidEl.textContent.trim();
      const aid = aidEl.textContent.trim();
      if (groupIds.includes(gid)) deps[gid].add(aid);
    }
  }

  // helper to derive alias
  const alias = (gid: string) => gid.split(".").pop()!;

  // build DSL with template literal
  const groupsBlock = groupIds.map((gid) => {
    const arts = Array.from(deps[gid]).sort();
    return `
    ${alias(gid)} = intGroup '${gid}' {
${arts.map(a => `      ${a} = artifact`).join("\n")}
    }`.trimEnd();
  }).join("\n\n");

  const dsl = `
specification {
  element libsSystem
  element group
  element artifact
}

// Deployment model
model {
  libsSystem all {
${groupsBlock}
  }
}

views {
  view libView {
    title 'Library Deployment'

    include all,all.**
  }
}
`.trim();

  console.log(dsl);
}

await new Command()
  .name("extract-deps")
  .version("0.1")
  .description("Extract dependencies from pom.xml and generate a C4-like DSL.")
  .option(
    "--workdir <dir:string>",
    "Working directory to search",
    { default: "." },
  )
  .option(
    "--group <groupId:string>",
    "Group ID to include (can be specified multiple times)",
    { collect: true, required: true },
  )
  .option(
    "--max-depth <n:number>",
    "Max recursion depth for searching pom.xml",
    { default: 2 },
  )
  .action(async (options) => {
    await extractDeps(
      options.workdir,
      options.group,
      options.maxDepth,
    );
  })
  .parse(Deno.args);
