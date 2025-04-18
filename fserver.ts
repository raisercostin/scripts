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

serve(async (req) => {
  const { method, headers } = req;
  const pathname = new URL(req.url).pathname;

  if (method === "GET" && pathname === "/") {
    const body = await Deno.readFile("./index.html");
    return new Response(body, { headers: { "content-type": "text/html" } });
  }

  if (method === "POST" && pathname === "/save") {
    if (!checkAuth(headers)) {
      return new Response("Unauthorized", {
        status: 401,
        headers: { "WWW-Authenticate": `Basic realm="${REALM}"` },
      });
    }
    const content = await req.text();
    await Deno.writeTextFile("diagram.c4", content);
    return new Response("Saved");
  }

  return new Response("Not Found", { status: 404 });
});