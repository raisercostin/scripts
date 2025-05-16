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
  const projectScm: Record<string, string> = {};
  const projToGroup: Record<string, string> = {};
  const edges: Edge[] = [];

  for (const p of groupPrefixes) {
    deps[p] = new Set();
  }

  // recurse & parse
  async function walk(dir: string, depth: number) {
    if (depth > maxDepth) return;

    for await (const e of Deno.readDir(dir)) {
      const full = join(dir, e.name);

      if (e.isFile && e.name === "pom.xml") {
        logProgress(`Parsing ${full}…`);
        const xml = await Deno.readTextFile(full);
        const doc = new DOMParser().parseFromString(xml, "text/html");
        if (!doc) continue;

        // coordinates
        const projectGroupId = doc
          .querySelector("project > groupId")?.textContent.trim()
          ?? doc
            .querySelector("project > parent > groupId")
            ?.textContent.trim()
          ?? "";
        const projectArtifact = doc
          .querySelector("project > artifactId")?.textContent.trim()
          ?? "";

        projToGroup[projectArtifact] = projectGroupId;

        // add to deps if internal
        const isProjectInternal = groupPrefixes.some(p =>
          projectGroupId.startsWith(p)
        );
        if (isProjectInternal) {
          for (const p of groupPrefixes.filter(pr => projectGroupId.startsWith(pr))) {
            deps[p].add(projectArtifact);
          }
        }

        // scm connection
        const conn = doc
          .querySelector("project > scm > connection")
          ?.textContent.trim();
        if (conn) projectScm[projectArtifact] = conn;

        // parent relationship
        const parentGidEl = doc.querySelector("project > parent > groupId");
        const parentAidEl = doc.querySelector("project > parent > artifactId");
        if (parentGidEl && parentAidEl) {
          const parentGid = parentGidEl.textContent.trim();
          const parentAid = parentAidEl.textContent.trim();
          const parentInternal = groupPrefixes.some(p =>
            parentGid.startsWith(p)
          );
          edges.push({
            src: projectArtifact,
            dst: parentAid,
            scope: "parent",
            isSrcInternal: true,
            isDstInternal: parentInternal,
          });
          logProgress(`Parent edge: ${projectArtifact} -> ${parentAid}`);
        }

        // modules (resolve their own POMs)
        const moduleEls = doc.querySelectorAll("project > modules > module");
        if (moduleEls.length) {
          projectModules[projectArtifact] = new Set();
          for (const m of moduleEls) {
            const modDir = join(dir, m.textContent.trim());
            const modPom = join(modDir, "pom.xml");
            try {
              const modXml = await Deno.readTextFile(modPom);
              const modDoc = new DOMParser().parseFromString(
                modXml,
                "text/html",
              );
              if (!modDoc) throw new Error();
              const mgid =
                modDoc.querySelector("project > groupId")?.textContent.trim() ??
                projectGroupId;
              const maid =
                modDoc.querySelector("project > artifactId")
                  ?.textContent.trim() ??
                "";
              projToGroup[maid] = mgid;
              projectModules[projectArtifact].add(maid);
              // collect SCM for module if present
              const mconn = modDoc
                .querySelector("project > scm > connection")
                ?.textContent.trim();
              if (mconn) projectScm[maid] = mconn;
              // module edge
              edges.push({
                src: projectArtifact,
                dst: maid,
                scope: "module",
                isSrcInternal: true,
                isDstInternal: true,
              });
              logProgress(`Module edge: ${projectArtifact} -> ${maid}`);
            } catch {
              logProgress(`Skipping module without POM: ${modDir}`);
            }
          }
        }

        // dependencies (skip dependencyManagement)
        for (const dep of doc.querySelectorAll(
          "project > dependencies > dependency",
        )) {
          if (dep.closest("dependencyManagement")) {
            logProgress("Ignoring dependency in <dependencyManagement>");
            continue;
          }
          const gid = dep
            .querySelector("groupId")
            ?.textContent.trim() ?? projectGroupId;
          const aid = dep.querySelector("artifactId")?.textContent.trim() ?? "";
          const scope = dep.querySelector("scope")?.textContent.trim() ??
            "compile";

          const depInternal = groupPrefixes.some(p => gid.startsWith(p));
          if (isProjectInternal || depInternal) {
            if (depInternal) {
              for (const p of groupPrefixes.filter(pr => gid.startsWith(pr))) {
                deps[p].add(aid);
              }
              logProgress(`Found dependency: ${gid} -> ${aid} [${scope}]`);
            } else {
              logProgress(`Skipping details for external ${gid}`);
            }
            edges.push({
              src: projectArtifact,
              dst: aid,
              scope,
              isSrcInternal: isProjectInternal,
              isDstInternal: depInternal,
            });
          } else {
            logProgress(`Skipping external dep: ${gid}:${aid}`);
          }
        }
      } else if (e.isDirectory) {
        await walk(full, depth + 1);
      }
    }
  }

  await walk(baseDir, 0);

  logProgress(
    `Graph has ${Object.keys(projToGroup).length} projects, ${
      edges.length
    } edges.`,
  );

  console.log(
    buildDsl(
      deps,
      projectModules,
      projectScm,
      edges,
      projToGroup,
      groupPrefixes,
      { showEdgeToExternal, showEdgeFromExternal },
    ),
  );
}

function buildDsl(
  deps: Record<string, Set<string>>,
  projectModules: Record<string, Set<string>>,
  projectScm: Record<string, string>,
  edges: Edge[],
  projToGroup: Record<string, string>,
  groupPrefixes: string[],
  options: { showEdgeToExternal: boolean; showEdgeFromExternal: boolean },
): string {
  const { showEdgeToExternal, showEdgeFromExternal } = options;
  const alias = (g: string) => g.split(".").pop()!;

  // ── specification
  const rels = ["compile", "runtime", "provided", "test", "pom", "module", "parent"];
  const spec = [
    "specification {",
    "  element libsSystem",
    "  element group",
    "  element project",
    "  element module",
    "  element artifact",
    ...rels.map(r => `  relationship ${r}`),
    "  tag module",
    "}",
  ];

  // ── model
  const blocks = groupPrefixes.map(pref => {
    const grp = alias(pref);
    const lines = [`    ${grp} = group '${pref}' {`];

    // each project in this prefix
    for (const proj of [...new Set(Object.keys(projectModules).concat(Object.keys(projectScm)))] ) {
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
      if(projectModules[a]) continue;
      lines.push(`      ${a} = artifact`);
    }

    lines.push("    }");
    return lines.join("\n");
  });

  // ── edges
  const edgeLines = edges
    .filter(e => {
      if (e.isSrcInternal && e.isDstInternal) return true;
      if (e.isSrcInternal && !e.isDstInternal) return showEdgeToExternal;
      if (!e.isSrcInternal && e.isDstInternal) return showEdgeFromExternal;
      return false;
    })
    .map(e => `    ${e.src} -[${e.scope}]-> ${e.dst} '${e.scope}'`);

  return `
    ${spec.join("\n")}
    model {
      libsSystem all {
        ${blocks.join("\n")}
        ${edgeLines.join("\n")}
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
  `.replaceAll(/^\s{4}/gm, "")
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
