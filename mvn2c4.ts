#!/usr/bin/env deno run --allow-read --allow-write

import { Command } from "https://deno.land/x/cliffy@v0.25.7/command/mod.ts";
import { dirname, join, normalize } from "https://deno.land/std@0.160.0/path/mod.ts";
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
  filterArtifactId: string[];
}

type Edge = {
  src: string;
  dst: string;
  scope: string;
  isSrcInternal: boolean;
  isDstInternal: boolean;
};

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

function matchesWildcard(name: string, patterns: string[]): boolean {
  return patterns.some(pat => {
    const regex = new RegExp("^" + pat.replace(/\*/g, ".*") + "$");
    return regex.test(name);
  });
}

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

function determineGroupPrefixes(
  user: string[],
  discovered: Set<string>,
): string[] {
  return user.length > 0 ? user : Array.from(discovered);
}

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

function processRawProjects(
  raw: RawProj[],
  groupPrefixes: string[],
  storage: Storage,
  filterPatterns: string[],
) {
  const { deps, projectModules, projectScm, projToGroup, edgeList } = storage;

  for (const rp of raw) {
    if (filterPatterns.length > 0 &&
        !matchesWildcard(rp.artifactId, filterPatterns)) {
      logProgress(`Filtering out project: ${rp.artifactId}`);
      continue;
    }

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
      if (filterPatterns.length > 0 &&
          !matchesWildcard(m.artifactId, filterPatterns)) {
        logProgress(`Filtering out module: ${m.artifactId}`);
        continue;
      }
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
      if (filterPatterns.length > 0 &&
          !matchesWildcard(d.artifactId, filterPatterns)) {
        logProgress(`Filtering out dependency: ${d.artifactId}`);
        continue;
      }
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

async function outputDsl(dsl: string, outPath: string) {
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

async function extractDeps(opts: CliOptions) {
  const baseDir = normalize(opts.workdir);
  logProgress(`Scanning '${baseDir}' for pom.xml (maxDepth=${opts.maxDepth})…`);

  const { raw, discoveredGroups } = await collectRawProjects(
    baseDir,
    opts.maxDepth,
  );

  const groupPrefixes = determineGroupPrefixes(
    opts.group,
    discoveredGroups,
  );
  logProgress(`Using group prefixes: ${groupPrefixes.join(", ")}`);

  const storage = initializeStorage(groupPrefixes);

  processRawProjects(
    raw,
    groupPrefixes,
    storage,
    opts.filterArtifactId,
  );

  logProgress(
    `Graph has ${Object.keys(storage.projToGroup).length} projects and ${
      storage.edgeList.length
    } edges.`,
  );

  const dsl = buildDsl(
    storage.deps,
    storage.projectModules,
    storage.projectScm,
    storage.edgeList,
    storage.projToGroup,
    groupPrefixes,
    { showEdgeToExternal: opts.showEdgeToExternal, showEdgeFromExternal: opts.showEdgeFromExternal },
  );

  await outputDsl(dsl, opts.out);
}

// ── CLI setup ───────────────────────────────────────────────────────────────

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

  return `
    specification {
      element libsSystem {
        style {
          opacity 10%
        }
      }
      element group {
        style {
          opacity 10%
        }
      }
      element project
      element module
      element artifact
      relationship compile
      relationship runtime
      relationship provided
      relationship test
      relationship pom
      relationship module
      relationship parent
      tag module
    }
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
        exclude *<->* where kind is test
      }
      view mvnView {
        title 'Maven Modules/Parents'

        include all,all.**
      }
    }
    `.replaceAll(/^\s{4}/gm, "");
}await new Command()
  .name("mvn2c4 Converter")
  .version("0.1")
  .description("Convert pom.xml trees into a C4-like DSL")
  .option("--workdir <dir:string>", "Working directory", { default: "." })
  .option("--group <prefix:string>", "Group prefix (repeatable)", { collect: true, default: [] })
  .option("--max-depth <n:number>", "Recursion depth", { default: 2 })
  .option("--showEdgeToExternal", "Include edges to external", { default: false })
  .option("--showEdgeFromExternal", "Include edges from external", { default: false })
  .option("--filterArtifactId <pattern:string>", "Filter artifact IDs (wildcard)", { collect: true, default: [] })
  .option("--out <file:string>", "Output file (creates dirs)", { default: "" })
  .action((opts: CliOptions) => extractDeps(opts))
  .parse(Deno.args);
