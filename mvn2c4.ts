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

type Edge = {
  src: string;
  dst: string;
  scope: string;
  isSrcInternal: boolean;
  isDstInternal: boolean;
};

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

  // storage
  const deps: Record<string, Set<string>> = {};
  const projectModules: Record<string, Set<string>> = {};
  const projectSubprojects: Record<string, Set<string>> = {};
  const projectScm: Record<string, string> = {};
  const allProjects = new Set<string>();
  const moduleArtifacts = new Set<string>();
  const edges: Edge[] = [];

  for (const p of groupPrefixes) {
    deps[p] = new Set();
  }

  // walk & parse
  async function walk(dir: string, depth: number) {
    if (depth > maxDepth) return;
    for await (const e of Deno.readDir(dir)) {
      const full = join(dir, e.name);
      if (e.isFile && e.name === "pom.xml") {
        logProgress(`Parsing ${full}…`);
        const xml = await Deno.readTextFile(full);
        const doc = new DOMParser().parseFromString(xml, "text/html");
        if (!doc) continue;

        const gid = doc.querySelector("project > groupId")?.textContent.trim()
          ?? doc.querySelector("project > parent > groupId")?.textContent.trim()
          ?? "";
        const aid = doc.querySelector("project > artifactId")?.textContent.trim() ?? "";
        const coord = `${gid}:${aid}`;
        allProjects.add(aid);

        // record SCM connection
        const conn = doc.querySelector("project > scm > connection")?.textContent.trim();
        if (conn) projectScm[aid] = conn;

        // detect modules
        const moduleEls = doc.querySelectorAll("project > modules > module");
        if (moduleEls.length) {
          projectModules[aid] = new Set();
          for (const m of moduleEls) {
            const mod = m.textContent.trim();
            projectModules[aid].add(mod);
            moduleArtifacts.add(mod);
            edges.push({
              src: aid,
              dst: mod,
              scope: "module",
              isSrcInternal: true,
              isDstInternal: true,
            });
            logProgress(`Module: ${coord} → ${mod}`);
          }
        } else {
          projectSubprojects[aid] = new Set();
        }

        // dependencies (skip dependencyManagement)
        for (const dep of doc.querySelectorAll("project > dependencies > dependency")) {
          if (dep.closest("dependencyManagement")) {
            logProgress("Ignoring dependency in <dependencyManagement>");
            continue;
          }
          const dg = dep.querySelector("groupId")?.textContent.trim() ?? gid;
          const da = dep.querySelector("artifactId")?.textContent.trim();
          const sc = dep.querySelector("scope")?.textContent.trim() ?? "compile";
          if (!da) continue;

          const projInt = groupPrefixes.some(p => gid.startsWith(p));
          const depInt = groupPrefixes.some(p => dg.startsWith(p));
          if (projInt || depInt) {
            if (depInt) {
              deps[groupPrefixes.find(p => dg.startsWith(p))!].add(da);
              logProgress(`Dep: ${dg}:${da} [${sc}]`);
            } else {
              logProgress(`Skipping internal-only logic for ${dg}`);
            }
            edges.push({
              src: aid,
              dst: da,
              scope: sc,
              isSrcInternal: projInt,
              isDstInternal: depInt,
            });
          } else {
            logProgress(`Skipping external dep: ${dg}:${da}`);
          }
        }

      } else if (e.isDirectory) {
        await walk(full, depth + 1);
      }
    }
  }

  await walk(baseDir, 0);

  logProgress(
    `Graph: ${allProjects.size} projects, ${moduleArtifacts.size} modules, ${edges.length} edges.`
  );

  console.log(buildDsl(
    deps,
    projectModules,
    projectSubprojects,
    projectScm,
    edges,
    groupPrefixes,
    { showEdgeToExternal, showEdgeFromExternal }
  ));
}

function buildDsl(
  deps: Record<string, Set<string>>,
  projectModules: Record<string, Set<string>>,
  projectSubprojects: Record<string, Set<string>>,
  projectScm: Record<string, string>,
  edges: Edge[],
  groupPrefixes: string[],
  options: { showEdgeToExternal: boolean; showEdgeFromExternal: boolean },
): string {
  const { showEdgeToExternal, showEdgeFromExternal } = options;
  const alias = (g: string) => g.split(".").pop()!;

  // spec
  const rels = ["compile", "runtime", "provided", "test", "pom", "module"];
  const spec = [
    "specification {",
    "  element libsSystem",
    "  element project",
    "  element module",
    "  element subproject",
    "  element artifact",
    "  element group",
    ...rels.map(r => `  relationship ${r}`),
    "  tag module",
    "}",
  ];

  // model
  const blocks = groupPrefixes.map(pref => {
    const grp = alias(pref);
    const lines = [`    ${grp} = group '${pref}' {`];

    // each project under this prefix
    for (const [proj, mods] of Object.entries(projectModules)) {
      if (!proj.startsWith(alias(pref))) continue;
      const scm = projectScm[proj] ? ` #scm='${projectScm[proj]}'` : "";
      lines.push(`      ${proj} = project${scm} {`);
      for (const m of mods) lines.push(`        ${m} = module`);
      lines.push("      }");
    }
    for (const [proj] of Object.entries(projectSubprojects)) {
      if (!proj.startsWith(alias(pref))) continue;
      if (projectModules[proj]?.size) continue; // skip modules
      const scm = projectScm[proj] ? ` #scm='${projectScm[proj]}'` : "";
      lines.push(`      ${proj} = subproject${scm}`);
    }

    // artifacts/dependencies
    const arts = [...deps[pref]].sort();
    for (const a of arts) {
      const tag = projectModules[a] ? " #module" : "";
      lines.push(`      ${a} = artifact${tag}`);
    }

    lines.push("    }");
    return lines.join("\n");
  });

  const edgeLines = edges
    .filter(e => {
      if (e.isSrcInternal && e.isDstInternal) return true;
      if (e.isSrcInternal && !e.isDstInternal) return showEdgeToExternal;
      if (!e.isSrcInternal && e.isDstInternal) return showEdgeFromExternal;
      return false;
    })
    .map(e => `    ${e.src} -[${e.scope}]-> ${e.dst} '${e.scope}'`);

  const model = [
    "",
    "model {",
    "  libsSystem all {",
    ...blocks,
    ...(edgeLines.length ? ["", ...edgeLines] : []),
    "  }",
    "}",
  ];

  const views = [
    "",
    "views {",
    "  view libView {",
    "    title 'Library Deployment'",
    "",
    "    include all,all.**",
    "  }",
    "}",
  ];

  return [...spec, ...model, ...views].join("\n");
}

await new Command()
  .name("mvn2c4 Converter")
  .version("0.1")
  .description("Convert pom.xml trees into a C4-like DSL with projects/modules/subprojects.")
  .option("--workdir <dir:string>", "Working directory", { default: "." })
  .option("--group <prefix:string>", "Group prefix (can repeat)", { collect: true, required: true })
  .option("--max-depth <n:number>", "Recursion depth", { default: 2 })
  .option("--showEdgeToExternal", "Edges to external", { default: false })
  .option("--showEdgeFromExternal", "Edges from external", { default: false })
  .action((opts: CliOptions) => extractDeps(opts))
  .parse(Deno.args);
