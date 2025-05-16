#!/usr/bin/env deno run --allow-read --allow-write

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import {
  join,
  normalize,
  dirname,
} from "https://deno.land/std@0.160.0/path/mod.ts";
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
  out: string;
}

type Edge = {
  src: string;
  dst: string;
  scope: string;
  isSrcInternal: boolean;
  isDstInternal: boolean;
};
// … your imports and type definitions above …

interface CliOptions {
  workdir: string;
  group: string[];
  maxDepth: number;
  showEdgeToExternal: boolean;
  showEdgeFromExternal: boolean;
  out: string;
}

type RawProj = {
  groupId: string;
  artifactId: string;
  scm?: string;
  parent?: { groupId: string; artifactId: string };
  modules: Array<{ groupId: string; artifactId: string; scm?: string }>;
  deps: Array<{ groupId: string; artifactId: string; scope: string }>;
};

type Storage = {
  deps: Record<string, Set<string>>;
  projectModules: Record<string, Set<string>>;
  projectScm: Record<string, string>;
  projToGroup: Record<string, string>;
  edgeList: Edge[];
};

// ── Phase 1: crawl filesystem and collect raw POM data ──────────────────────
async function collectRawProjects(
  baseDir: string,
  maxDepth: number,
): Promise<{ raw: RawProj[]; discoveredGroups: Set<string> }> {
  const raw: RawProj[] = [];
  const discoveredGroups = new Set<string>();

  async function walk(dir: string, depth: number) {
    if (depth > maxDepth) return;
    for await (const e of Deno.readDir(dir)) {
      const full = join(dir, e.name);
      if (e.isDirectory) {
        await walk(full, depth + 1);
      } else if (e.isFile && e.name === "pom.xml") {
        logProgress(`Parsing ${full}…`);
        const xml = await Deno.readTextFile(full);
        const doc = new DOMParser().parseFromString(xml, "text/html");
        if (!doc) continue;

        const gid = doc.querySelector("project > groupId")?.textContent.trim()
          ?? doc.querySelector("project > parent > groupId")?.textContent.trim()
          ?? "";
        const aid = doc.querySelector("project > artifactId")?.textContent.trim() ?? "";
        discoveredGroups.add(gid);

        const rp: RawProj = {
          groupId: gid,
          artifactId: aid,
          scm: doc.querySelector("project > scm > connection")?.textContent.trim(),
          parent: (() => {
            const pg = doc.querySelector("project > parent > groupId")?.textContent.trim();
            const pa = doc.querySelector("project > parent > artifactId")?.textContent.trim();
            return pg && pa ? { groupId: pg, artifactId: pa } : undefined;
          })(),
          modules: [],
          deps: [],
        };

        // collect modules
        for (const m of doc.querySelectorAll("project > modules > module")) {
          const modDir = join(dir, m.textContent.trim());
          const modPom = join(modDir, "pom.xml");
          try {
            const modXml = await Deno.readTextFile(modPom);
            const modDoc = new DOMParser().parseFromString(modXml, "text/html");
            if (!modDoc) throw new Error();
            rp.modules.push({
              groupId: modDoc.querySelector("project > groupId")?.textContent.trim() ?? gid,
              artifactId: modDoc.querySelector("project > artifactId")?.textContent.trim() ?? "",
              scm: modDoc.querySelector("project > scm > connection")?.textContent.trim(),
            });
          } catch {
            logProgress(`Skipping module without POM: ${modDir}`);
          }
        }

        // collect dependencies
        for (const dep of doc.querySelectorAll("project > dependencies > dependency")) {
          if (dep.closest("dependencyManagement")) continue;
          rp.deps.push({
            groupId: dep.querySelector("groupId")?.textContent.trim() ?? gid,
            artifactId: dep.querySelector("artifactId")?.textContent.trim() ?? "",
            scope: dep.querySelector("scope")?.textContent.trim() ?? "compile",
          });
        }

        raw.push(rp);
      }
    }
  }

  await walk(baseDir, 0);
  return { raw, discoveredGroups };
}

// ── Phase 2: decide which group-prefixes to use ────────────────────────────
function determineGroupPrefixes(
  userGroups: string[],
  discovered: Set<string>,
): string[] {
  return userGroups?.length > 0 ? userGroups : Array.from(discovered);
}

// ── Phase 3: init empty storage for replay ─────────────────────────────────
function initializeStorage(groups: string[]): Storage {
  const deps: Record<string, Set<string>> = {};
  groups.forEach(g => (deps[g] = new Set()));

  return {
    deps,
    projectModules: {},
    projectScm: {},
    projToGroup: {},
    edgeList: [],
  };
}

// ── Phase 4: replay raw data into our storage, applying filters ───────────
function processRawProjects(
  raw: RawProj[],
  groupPrefixes: string[],
  storage: Storage,
) {
  const { deps, projectModules, projectScm, projToGroup, edgeList } = storage;

  for (const rp of raw) {
    const { groupId, artifactId, scm, parent, modules, deps: rdeps } = rp;
    const isProjInt = groupPrefixes.some(p => groupId.startsWith(p));

    projToGroup[artifactId] = groupId;
    if (scm) projectScm[artifactId] = scm;
    if (isProjInt) {
      groupPrefixes.filter(p => groupId.startsWith(p))
        .forEach(p => deps[p].add(artifactId));
    }

    if (parent) {
      const parentInt = groupPrefixes.some(p => parent.groupId.startsWith(p));
      edgeList.push({
        src: artifactId,
        dst: parent.artifactId,
        scope: "parent",
        isSrcInternal: true,
        isDstInternal: parentInt,
      });
    }

    for (const m of modules) {
      projectModules[artifactId] ??= new Set();
      projectModules[artifactId].add(m.artifactId);
      if (m.scm) projectScm[m.artifactId] = m.scm;
      edgeList.push({
        src: artifactId,
        dst: m.artifactId,
        scope: "module",
        isSrcInternal: isProjInt,
        isDstInternal: true,
      });
    }

    for (const d of rdeps) {
      const depInt = groupPrefixes.some(p => d.groupId.startsWith(p));
      if (isProjInt || depInt) {
        if (depInt) {
          groupPrefixes.filter(p => d.groupId.startsWith(p))
            .forEach(p => deps[p].add(d.artifactId));
        }
        edgeList.push({
          src: artifactId,
          dst: d.artifactId,
          scope: d.scope,
          isSrcInternal: isProjInt,
          isDstInternal: depInt,
        });
      }
    }
  }
}

// ── Phase 5: emit or write out the DSL ─────────────────────────────────────
async function outputDsl(
  dsl: string,
  outPath: string,
) {
  if (!outPath) {
    console.log(dsl);
  } else {
    const file = normalize(outPath);
    const dir = dirname(file);
    await Deno.mkdir(dir, { recursive: true });
    await Deno.writeTextFile(file, dsl);
    console.log(`DSL written to ${file}`);
  }
}

// ── Main orchestrator ─────────────────────────────────────────────────────
async function extractDeps(opts: CliOptions) {
  const baseDir = normalize(opts.workdir);
  logProgress(`Scanning '${baseDir}' for pom.xml (maxDepth=${opts.maxDepth})…`);

  // 1. collect raw POM data
  const { raw, discoveredGroups } = await collectRawProjects(
    baseDir,
    opts.maxDepth,
  );

  // 2. decide groups
  const groupPrefixes = determineGroupPrefixes(opts.group, discoveredGroups);
  logProgress(`Using group prefixes: ${groupPrefixes.join(", ")}`);

  // 3. init storage
  const storage = initializeStorage(groupPrefixes);

  // 4. process raw data
  processRawProjects(raw, groupPrefixes, storage);

  logProgress(
    `Graph: ${Object.keys(storage.projToGroup).length} projects, ` +
    `${storage.edgeList.length} edges.`
  );

  // 5. build and output DSL
  const dsl = buildDsl(
    storage.deps,
    storage.projectModules,
    storage.projectScm,
    storage.edgeList,
    storage.projToGroup,
    groupPrefixes,
    { showEdgeToExternal: opts.showEdgeToExternal,
      showEdgeFromExternal: opts.showEdgeFromExternal },
  );

  await outputDsl(dsl, opts.out);
}

function buildDsl(
  deps: Record<string, Set<string>>,
  projectModules: Record<string, Set<string>>,
  projectScm: Record<string, string>,
  edges: Edge[],
  projToGroup: Record<string, string>,
  groupPrefixes: string[],
  options: { showEdgeToExternal: boolean; showEdgeFromExternal: boolean }
): string {
  const { showEdgeToExternal, showEdgeFromExternal } = options;
  const alias = (g: string) => g.split(".").pop()!;

  // ── specification
  const rels = [
    "compile",
    "runtime",
    "provided",
    "test",
    "pom",
    "module",
    "parent",
  ];
  const spec = [
    "specification {",
    "  element libsSystem",
    "  element group",
    "  element project",
    "  element module",
    "  element artifact",
    ...rels.map((r) => `  relationship ${r}`),
    "  tag module",
    "}",
  ];

  // ── model
  const blocks = groupPrefixes.map((pref) => {
    const grp = alias(pref);
    const lines = [`    ${grp} = group '${pref}' {`];

    // each project in this prefix
    for (const proj of [
      ...new Set(Object.keys(projectModules).concat(Object.keys(projectScm))),
    ]) {
      // need full groupId to filter:
      // assume you recorded a map projToGroup[proj] earlier
      if (!projToGroup[proj].startsWith(pref)) continue;

      lines.push(`      ${proj} = project {`);
      const url = projectScm[proj]?.replace(/^scm:git:/, "");
      if (url) lines.push(`        link ${url}`);
      //do not render modules
      // for (const m of projectModules[proj] ?? []) {
      //   lines.push(`        ${m} = module`);
      // }
      lines.push("      }");
    }

    // stand-alone artifacts
    for (const a of [...deps[pref]].sort()) {
      if (projectModules[a]) continue;
      lines.push(`      ${a} = artifact`);
    }

    lines.push("    }");
    return lines.join("\n    ");
  });

  // ── edges
  const edgeLines = edges
    .filter((e) => {
      if (e.isSrcInternal && e.isDstInternal) return true;
      if (e.isSrcInternal && !e.isDstInternal) return showEdgeToExternal;
      if (!e.isSrcInternal && e.isDstInternal) return showEdgeFromExternal;
      return false;
    })
    .map((e) => `    ${e.src} -[${e.scope}]-> ${e.dst} '${e.scope}'`);

  return `${spec.join("\n")}
    model {
      libsSystem all {
    ${blocks.join("\n    ")}
    ${edgeLines.join("\n    ")}
      }
    }

    views {
      view libView {
        title 'Library Deployment'

        include all,all.**
        exclude *<->* where kind is parent
        exclude *<->* where kind is module
      }
      view mvnView {
        title 'Maven Modules/Parents'

        include all,all.**
      }
    }
    `.replaceAll(/^\s{4}/gm, "");
}

await new Command()
  .name("mvn2c4 Converter")
  .version("0.1")
  .description(
    "Convert pom.xml trees into a C4-like DSL with projects/modules/subprojects."
  )
  .option("--workdir <dir:string>", "Working directory", { default: "." })
  .option("--group <prefix:string>", "Group prefix (can repeat)", {
    collect: true
  })
  .option("--max-depth <n:number>", "Recursion depth", { default: 2 })
  .option("--showEdgeToExternal", "Edges to external", { default: false })
  .option("--showEdgeFromExternal", "Edges from external", { default: false })
  .option(
    "--out <file:string>",
    "Write DSL to this file (creates directories)",
    { default: "" }
  )
  .action((opts: CliOptions) => extractDeps(opts))
  .parse(Deno.args);
