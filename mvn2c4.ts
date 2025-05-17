#!/usr/bin/env deno run --allow-read

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { join, normalize, sep } from "https://deno.land/std@0.160.0/path/mod.ts";
import { DOMParser } from "https://deno.land/x/deno_dom/deno-dom-wasm.ts";

function logProgress(message: string) {
  console.error(message);
}
interface CliOptions {
  workdir: string;
  group: string[];
  maxDepth: number;
  showEdgeToExternal: boolean;
  showEdgeFromExternal: boolean;
}

async function extractDeps(opts: CliOptions) {
  const {
    workdir,
    group: groupPrefixes,
    maxDepth,
    showEdgeToExternal,
    showEdgeFromExternal,
  } = opts;

  const baseDir = normalize(workdir);
  logProgress(`Scanning '${baseDir}' for pom.xml (maxDepth=${maxDepth})…`);

  // prepare storage
  const deps: Record<string, Set<string>> = {};
  const prefixModules: Record<string, Set<string>> = {};
  for (const p of groupPrefixes) {
    deps[p] = new Set();
    prefixModules[p] = new Set();
  }

  type Edge = {
    src: string;
    dst: string;
    scope: string;
    isSrcInternal: boolean;
    isDstInternal: boolean;
  };
  const edges: Edge[] = [];

  // recurse & parse in one pass
  async function walk(dir: string, depth: number) {
    if (depth > maxDepth) return;
    for await (const e of Deno.readDir(dir)) {
      const full = join(dir, e.name);
      if (e.isFile && e.name === "pom.xml") {
        logProgress(`Parsing ${full}…`);
        const xml = await Deno.readTextFile(full);
        const doc = new DOMParser().parseFromString(xml, "text/html");
        if (!doc) continue;

        // project coordinates
        const projectGroupId = doc
          .querySelector("project > groupId")?.textContent.trim()
          ?? doc.querySelector("project > parent > groupId")?.textContent.trim()
          ?? "";
        const projectArtifact = doc
          .querySelector("project > artifactId")?.textContent.trim()
          ?? "";

        // modules
        const moduleEls = doc.querySelectorAll("project > modules > module");
        const projectPrefixes = groupPrefixes.filter(p => projectGroupId.startsWith(p));
        const isProjectInternal = projectPrefixes.length > 0;
        if (isProjectInternal) {
          projectPrefixes.forEach(p => deps[p].add(projectArtifact));
          for (const m of moduleEls) {
            const modName = m.textContent.trim();
            projectPrefixes.forEach(p => prefixModules[p].add(modName));
            edges.push({
              src: projectArtifact,
              dst: modName,
              scope: "module",
              isSrcInternal: true,
              isDstInternal: true,
            });
            logProgress(`Found module: ${projectGroupId}:${modName}`);
          }
        }

        // dependencies
        for (const dep of doc.querySelectorAll("dependency")) {
          // skip dependencyManagement entries
          if (dep.closest("dependencyManagement")) {
            logProgress("Ignoring dependency in <dependencyManagement>");
            continue;
          }

          const gidEl = dep.querySelector("groupId");
          const aidEl = dep.querySelector("artifactId");
          const scopeEl = dep.querySelector("scope");
          const gid = gidEl?.textContent.trim() ?? projectGroupId;
          if (!aidEl) continue;
          const aid = aidEl.textContent.trim();
          const scope = scopeEl?.textContent.trim() ?? "compile";

          const depPrefixes = groupPrefixes.filter(p => gid.startsWith(p));
          const isDepInternal = depPrefixes.length > 0;

          if (isProjectInternal || isDepInternal) {
            if (isDepInternal) {
              depPrefixes.forEach(p => deps[p].add(aid));
              logProgress(`Found dependency: ${gid} -> ${aid} [${scope}]`);
            } else {
              logProgress(`Skipping dependency: ${gid} not in groupPrefixes`);
            }
            edges.push({
              src: projectArtifact,
              dst: aid,
              scope,
              isSrcInternal: isProjectInternal,
              isDstInternal: isDepInternal,
            });
          } else {
            logProgress(`Skipping dependency: ${gid} not in groupPrefixes`);
          }
        }
      } else if (e.isDirectory) {
        await walk(full, depth + 1);
      }
    }
  }

  await walk(baseDir, 0);

  logProgress(
    `Graph has ${
      new Set(edges.flatMap(e => [e.src, e.dst])).size
    } nodes and ${edges.length} edges.`,
  );

  // now build and print the DSL
  console.log(buildDsl(deps, prefixModules, edges, groupPrefixes, {
    showEdgeToExternal,
    showEdgeFromExternal,
  }));
}

function buildDsl(
  deps: Record<string, Set<string>>,
  prefixModules: Record<string, Set<string>>,
  edges: {
    src: string;
    dst: string;
    scope: string;
    isSrcInternal: boolean;
    isDstInternal: boolean;
  }[],
  groupPrefixes: string[],
  options: { showEdgeToExternal: boolean; showEdgeFromExternal: boolean },
): string {
  const { showEdgeToExternal, showEdgeFromExternal } = options;
  const alias = (gid: string) => gid.split(".").pop()!;

  // ── specification ─────────────────────────────────────────────────────────
  const relationships = ["compile", "runtime", "provided", "test", "pom", "module"];
  const specLines = [
    "specification {",
    "  element libsSystem",
    "  element group",
    "  element artifact",
    ...relationships.map(r => `  relationship ${r}`),
    "}",
  ];

  // ── model ─────────────────────────────────────────────────────────────────
  const groupBlocks = groupPrefixes.map(prefix => {
    const grp = alias(prefix);
    const artifactLines = [...deps[prefix]]
      .sort()
      .map(a => `      ${a} = artifact`)
      .join("\n");
    return [
      `    ${grp} = group '${prefix}' {`,
      artifactLines,
      "    }",
    ].join("\n");
  });

  const edgeLines = edges
    .filter(e => {
      if (e.isSrcInternal && e.isDstInternal) return true;
      if (e.isSrcInternal && !e.isDstInternal) return showEdgeToExternal;
      if (!e.isSrcInternal && e.isDstInternal) return showEdgeFromExternal;
      return false;
    })
    .map(e => `    ${e.src} -[${e.scope}]-> ${e.dst} '${e.scope}'`);

  const modelLines = [
    "model {",
    "  libsSystem all {",
    ...groupBlocks,
    ...(edgeLines.length ? ["", ...edgeLines] : []),
    "  }",
    "}",
  ];

  // ── views ─────────────────────────────────────────────────────────────────
  const viewLines = [
    "views {",
    "  view libView {",
    "    title 'Library Deployment'",
    "",
    "    include all,all.**",
    "  }",
    "}",
  ];

  return [...specLines, "", ...modelLines, "", ...viewLines].join("\n");
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
  .action(async (opts: CliOptions) => {
    await extractDeps(opts);
  })
  .parse(Deno.args);
