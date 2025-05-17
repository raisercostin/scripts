#!/usr/bin/env deno run --allow-read

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { join, normalize, sep } from "https://deno.land/std@0.160.0/path/mod.ts";
import { DOMParser } from "https://deno.land/x/deno_dom/deno-dom-wasm.ts";

// ── Find all pom.xml up to maxDepth ─────────────────────────────────────────
async function findPomFiles(dir: string, maxDepth: number, depth = 0): Promise<string[]> {
  if (depth > maxDepth) return [];
  const files: string[] = [];
  for await (const e of Deno.readDir(dir)) {
    const full = join(dir, e.name);
    if (e.isFile && e.name === "pom.xml") {
      files.push(full);
    } else if (e.isDirectory) {
      files.push(...await findPomFiles(full, maxDepth, depth + 1));
    }
  }
  return files;
}

// ── Log progress to stderr ──────────────────────────────────────────────────
function logProgress(message: string) {
  console.error(message);
}

function parseDependencies(xml: string, groupIds: string[]): Record<string, Set<string>> {
  const doc = new DOMParser().parseFromString(xml, "text/html");
  const result: Record<string, Set<string>> = {};
  for (const gid of groupIds) result[gid] = new Set();
  if (!doc) return result;

  // determine project-level groupId (including parent fallback)
  let projectGroupId = doc.querySelector("project > groupId")?.textContent.trim()
    ?? doc.querySelector("project > parent > groupId")?.textContent.trim()
    ?? "";

  for (const dep of doc.querySelectorAll("dependency")) {
    // try explicit groupId, otherwise use projectGroupId
    const gidEl = dep.querySelector("groupId");
    const aidEl = dep.querySelector("artifactId");
    const gid = gidEl ? gidEl.textContent.trim() : projectGroupId;
    if (!aidEl) continue;
    const aid = aidEl.textContent.trim();

    if (groupIds.includes(gid)) {
      result[gid].add(aid);
      logProgress(`Found dependency: ${gid} -> ${aid}`);
    } else {
      logProgress(`Skipping dependency: ${gid} not in groupIds`);
    }
  }
  return result;
}
// ── Merge multiple dep maps ─────────────────────────────────────────────────
function mergeDeps(
  accum: Record<string, Set<string>>,
  next: Record<string, Set<string>>,
) {
  for (const k of Object.keys(next)) {
    for (const a of next[k]) accum[k].add(a);
  }
}
interface Edge { src: string; dst: string; }

function buildGraph(
  deps: Record<string, Set<string>>,
  groupIds: string[],
): { nodes: Set<string>; edges: Edge[] } {
  const nodes = new Set<string>();
  const edges: Edge[] = [];

  const alias = (gid: string) => gid.split(".").pop()!;

  for (const gid of groupIds) {
    const grpNode = alias(gid);
    nodes.add(grpNode);

    for (const art of deps[gid]) {
      nodes.add(art);
      edges.push({ src: grpNode, dst: art });
    }
  }

  return { nodes, edges };
}
// ── Build the C4-like DSL ──────────────────────────────────────────────────
function buildDsl(deps: Record<string, Set<string>>, groupIds: string[]) {
  const alias = (gid: string) => gid.split(".").pop()!;
  const groups = groupIds.map(gid => {
    const arts = [...deps[gid]].sort();
    const lines = arts.map(a => `      ${a} = artifact`).join("\n");
    return `
    ${alias(gid)} = group '${gid}' {
${lines}
    }`.trimEnd();
  }).join("\n\n");

  return `
specification {
  element libsSystem
  element group
  element artifact
}

// Deployment model
model {
  libsSystem all {
${groups}
  }
}

views {
  view libView {
    title 'Library Deployment'

    include all,all.**
  }
}
`.trim();
}

async function extractDeps(
  workdir: string,
  groupIds: string[],
  maxDepth: number,
) {
  workdir = normalize(workdir);
  logProgress(`Scanning '${workdir}' for pom.xml (maxDepth=${maxDepth})…`);

  // prepare storage
  const deps: Record<string, Set<string>> = {};
  for (const gid of groupIds) deps[gid] = new Set();
  type Edge = { src: string; dst: string; scope: string };
  const edges: Edge[] = [];

  // recurse & parse in one pass
  async function walkAndParse(dir: string, depth: number) {
    if (depth > maxDepth) return;
    for await (const e of Deno.readDir(dir)) {
      const full = join(dir, e.name);
      if (e.isFile && e.name === "pom.xml") {
        logProgress(`Parsing ${full}…`);
        const xml = await Deno.readTextFile(full);
        const doc = new DOMParser().parseFromString(xml, "text/html");
        if (!doc) continue;

        // determine coordinates of this project
        const projectGroupId = doc
          .querySelector("project > groupId")?.textContent.trim()
          ?? doc.querySelector("project > parent > groupId")?.textContent.trim()
          ?? "";
        const projectArtifact = doc
          .querySelector("project > artifactId")?.textContent.trim()
          ?? "";

        for (const dep of doc.querySelectorAll("dependency")) {
          const gidEl = dep.querySelector("groupId");
          const aidEl = dep.querySelector("artifactId");
          const scopeEl = dep.querySelector("scope");
          const gid = gidEl?.textContent.trim() ?? projectGroupId;
          if (!aidEl) continue;
          const aid = aidEl.textContent.trim();
          const scope = scopeEl?.textContent.trim() ?? "compile";

          // only record if either side is in our groups
          if (groupIds.includes(gid) || groupIds.includes(projectGroupId)) {
            if (groupIds.includes(projectGroupId)) {
              deps[projectGroupId].add(projectArtifact);
              logProgress(`Found module: ${projectGroupId}:${projectArtifact}`);
            }
            if (groupIds.includes(gid)) {
              deps[gid].add(aid);
              logProgress(`Found dependency: ${gid} -> ${aid} [${scope}]`);
            }
            edges.push({ src: projectArtifact, dst: aid, scope });
          } else {
            logProgress(`Skipping dependency: ${gid} not in groupIds`);
          }
        }
      } else if (e.isDirectory) {
        await walkAndParse(full, depth + 1);
      }
    }
  }

  await walkAndParse(workdir, 0);

  // report graph size
  logProgress(`Graph has ${new Set(edges.flatMap(e => [e.src, e.dst])).size} nodes and ${edges.length} edges.`);

  // build group blocks
  const alias = (gid: string) => gid.split(".").pop()!;
  const groupBlocks = groupIds.map(gid => {
    const arts = [...deps[gid]].sort();
    const artLines = arts.map(a => `      ${a} = artifact`).join("\n");
    return `
    ${alias(gid)} = group '${gid}' {
${artLines}
    }`.trimEnd();
  }).join("\n\n");

  // build edge lines
  const edgeLines = edges
    .map(e => `    ${e.src} -[${e.scope}]-> ${e.dst} '${e.scope}'`)
    .join("\n");

  // assemble DSL
  const rels = ["compile", "runtime", "provided", "test", "pom"];
  const dsl = `
specification {
  element libsSystem
  element group
  element artifact
${rels.map(r => `  relationship ${r}`).join("\n")}
}

model {
  libsSystem all {
${groupBlocks}

${edgeLines}
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
  .name("mvn2c4 Converter")
  .version("0.1")
  .description(`Extract dependencies from pom.xml and emit a C4-like DSL.
    deno --allow-read c:\Users\CostinGrigore\work\costin\scripts-ts\mvn2c4.ts --group com.namekis
  `)
  .option("--workdir <dir:string>", "Working directory to search", { default: "." })
  .option("--group <groupId:string>", "Group ID(s) to include", { collect: true, required: true })
  .option("--max-depth <n:number>", "Max recursion depth", { default: 2 })
  .action(async (opts) => {
    await extractDeps(opts.workdir, opts.group, opts.maxDepth);
  })
  .parse(Deno.args);
