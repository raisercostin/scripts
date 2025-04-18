#!/usr/bin/env deno run --allow-net --allow-read --allow-write --allow-env
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";

const PASSWORD = Deno.env.get("PASSWORD") ?? "changeme";
const REALM = "C4 Editor";

function checkAuth(headers: Headers): boolean {
  const auth = headers.get("authorization") || "";
  if (!auth.startsWith("Basic ")) return false;
  const b64 = auth.split(" ")[1];
  const [user, pass] = atob(b64).split(":");
  return user === "admin" && pass === PASSWORD;
}

const editorFiles = {
  ".c4": new URL("./restfs-mermaid.html", import.meta.url),
};
const fileAssociations: Record<string, string> = {
  ".html": "text/html",
  ".c4":   "text/plain",
};

serve(async (req) => {
  const url = new URL(req.url);
  const pathname = url.pathname;
  const method = req.method;
  const headers = req.headers;
  const fsPath = `.${pathname}`;

  // Directory navigation
  try {
    const stat = await Deno.stat(fsPath);
    if (stat.isDirectory) {
      const wantsJson = req.headers.get('accept')?.includes('application/json');
      const entries = [];
      for await (const entry of Deno.readDir(fsPath)) {
        const name = entry.name + (entry.isDirectory ? '/' : '');
        const variants = [];
        if (!entry.isDirectory && entry.name.endsWith('.c4')) {
          const base = entry.name.slice(0, -3);
          variants.push(`${base}.svg`, `${base}.png`);
        }
        entries.push({ name, isDirectory: entry.isDirectory, variants });
      }
      if (wantsJson) {
        return new Response(JSON.stringify(entries), {
          headers: { 'content-type': 'application/json' }
        });
      } else {
        // existing HTML path, but for each .c4:
        //   … <a href="foo.svg">svg</a> <a href="foo.png">png</a>
        let list = '<ul>';
        for await (const entry of Deno.readDir(fsPath)) {
          const slash = entry.isDirectory ? '/' : '';
          const baseLink = `${pathname}${pathname.endsWith('/') ? '' : '/'}${entry.name}${slash}`;
          if (entry.isDirectory) {
            list += `<li><a href="${baseLink}">${entry.name}${slash}</a></li>`;
          } else {
            list += `<li><a href="${baseLink}">${entry.name}</a>`;
            if (entry.name.endsWith('.c4')) {
              list += ` (<a href="${baseLink}?edit">edit</a>)`;
            }
            list += `</li>`;
          }
        }
        list += '</ul>';
        return new Response(
          `<html><body><h1>Index of ${pathname}</h1>${list}</body></html>`,
          { headers: { 'content-type': 'text/html; charset=utf-8' } }
        );
      }
    }

    // File handling
    if (method === 'GET') {
      if (pathname.endsWith('.c4') && url.searchParams.has('edit')) {
        // serve the editor HTML from module-relative path
        const body = await Deno.readFile(editorFiles['.c4']);
        return new Response(body, { headers: { 'content-type': 'text/html' } });
      }
      const data = await Deno.readFile(fsPath);
      const ext = pathname.slice(pathname.lastIndexOf('.'));
      const ct = fileAssociations[ext] ?? 'application/octet-stream';
      return new Response(data, {
        headers: { 'content-type': `${ct}; charset=utf-8` },
      });
    }
  } catch (err) {
    if (!(err instanceof Deno.errors.NotFound)) {
      return new Response('Server Error', { status: 500 });
    }
    // not found → fall through
  }

  // POST /save
  if (method === 'POST' && pathname === '/save') {
    if (!checkAuth(headers)) {
      return new Response('Unauthorized', {
        status: 401,
        headers: { 'WWW-Authenticate': `Basic realm="${REALM}"` },
      });
    }
    const content = await req.text();
    await Deno.writeTextFile('diagram.c4', content);
    return new Response('Saved');
  }

  return new Response('Not Found', { status: 404 });
});
