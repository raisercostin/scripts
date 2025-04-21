#!/usr/bin/env -S deno run --allow-run --allow-read --allow-write --allow-net
// vlc_frame_server.ts
// Usage: deno run --allow-run --allow-read --allow-write --allow-net vlc_frame_server.ts <rtsp-url>

import { serve } from "https://deno.land/std@0.203.0/http/server.ts";

// Validate RTSP URL argument
if (Deno.args.length < 1) {
  console.error("Usage: deno run --allow-run --allow-read --allow-write --allow-net vlc_frame_server.ts <rtsp-url>");
  Deno.exit(1);
}
const rtspUrl = Deno.args[0];

const scenePrefix = "frame";
const sceneFile = `${scenePrefix}0001.jpg`;
const PORT = 8000;

// Launch VLC headless, dumping one frame per second (adjust scene-ratio to your stream's fps)
const vlcProcess = Deno.run({
  cmd: [
    "vlc",           // use 'cvlc' or 'vlc' on Windows
    "--intf", "dummy",
    "--dummy-quiet",
    "--video-filter", "scene",
    "--scene-path=.",
    `--scene-prefix=${scenePrefix}`,
    "--scene-format=jpg",
    "--scene-ratio=30",      // assuming 30 fps stream
    "--scene-replace",
    rtspUrl
  ],
  stdout: "null",
  stderr: "null",
});
console.log(`VLC started (PID: ${vlcProcess.pid}), dumping frames to ./${sceneFile}`);
console.log(`Server running at http://localhost:${PORT}`);

// Determine which signals to listen for (SIGBREAK for Windows)
const signals = Deno.build.os === "windows"
  ? ["SIGINT", "SIGBREAK"]
  : ["SIGINT", "SIGTERM"] as const;

for (const signal of signals) {
  Deno.addSignalListener(signal, () => {
    console.log(`Received ${signal}, stopping VLC (PID: ${vlcProcess.pid})...`);
    vlcProcess.kill("SIGKILL");
    vlcProcess.close();
    Deno.exit();
  });
}

// HTTP server serving HTML and the latest frame
serve((req) => {
  const url = new URL(req.url);
  if (url.pathname === "/") {
    const html = `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta http-equiv="refresh" content="1">
  <title>Live Frame</title>
</head>
<body>
  <img src="/frame.jpg" alt="Live frame" />
</body>
</html>`;
    return new Response(html, { headers: { "content-type": "text/html" } });
  }

  if (url.pathname === "/frame.jpg") {
    try {
      const data = Deno.readFileSync(sceneFile);
      return new Response(data, { headers: { "content-type": "image/jpeg" } });
    } catch {
      return new Response("Not ready", { status: 503 });
    }
  }

  return new Response("Not found", { status: 404 });
}, { port: PORT });