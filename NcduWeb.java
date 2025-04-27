//#!/usr/bin/env jbang
//DEPS io.javalin:javalin:5.6.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.2
//DEPS org.slf4j:slf4j-simple:2.0.7

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.stream.Stream;

public class NcduWeb {
  static final Path EXPORT_DIR = Paths.get("exports");
  static final Path UPLOAD_DIR = Paths.get("uploads");
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final int CACHE_SIZE = 8;

  static final Deque<CacheEntry> cache = new ArrayDeque<>();
  static final Lock cacheLock = new ReentrantLock();

  public static void main(String[] args) throws IOException {
    if (args.length > 0) {
      Path src = Paths.get(args[0]);
      if (Files.exists(src) && Files.isRegularFile(src)) {
        Files.createDirectories(EXPORT_DIR);
        Path dest = EXPORT_DIR.resolve(src.getFileName());
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Seeded export: " + dest);
      }
    }
    initDirs();

    var app = Javalin.create(cfg -> cfg.plugins.enableDevLogging()).start(7000);
    app.before(ctx -> System.out.println(ctx.method() + " " + ctx.path()));

    app.get("/", ctx -> {
      try (Stream<Path> stream = Files.list(EXPORT_DIR)) {
        Optional<Path> first = stream.filter(Files::isRegularFile).findFirst();
        if (first.isPresent()) {
          String fname = first.get().getFileName().toString();
          ctx.redirect("/" + fname);
          return;
        }
      }
      ctx.html(renderIndex());
    });

    app.post("/upload", ctx -> handleUpload(ctx, ctx.uploadedFile("f")));

    app.get("/{id}", ctx -> {
      String id = ctx.pathParam("id");
      handleBrowse(id, ctx);
    });

    System.out.println("NcduWeb running at http://localhost:7000");
  }

  static void initDirs() throws IOException {
    Files.createDirectories(EXPORT_DIR);
    Files.createDirectories(UPLOAD_DIR);
  }

  static void handleUpload(Context ctx, UploadedFile file) throws IOException {
    if (file == null || file.content() == null) {
      ctx.status(400).result("Invalid upload: no file provided");
      return;
    }
    String filename = file.filename();
    Files.createDirectories(EXPORT_DIR);
    Path target = EXPORT_DIR.resolve(filename);
    try (InputStream is = file.content()) {
      Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
    }
    ctx.redirect("/" + filename);
  }

  static void handleBrowse(String id, Context ctx) throws IOException {
    JsonNode root = lookup(id);
    if (root == null) {
      ctx.status(404).result("Not found");
      return;
    }
    ctx.contentType("text/html");
    ctx.result(renderBrowsePage(root, id));
  }

  static JsonNode lookup(String id) throws IOException {
    cacheLock.lock();
    try {
      for (CacheEntry e : cache) {
        if (e.id.equals(id)) {
          e.lastUsed = System.nanoTime();
          return e.data;
        }
      }
      Path filePath = EXPORT_DIR.resolve(id);
      if (!Files.exists(filePath) || !Files.isRegularFile(filePath))
        return null;
      JsonNode data = MAPPER.readTree(filePath.toFile());
      if (cache.size() >= CACHE_SIZE)
        cache.removeFirst();
      cache.addLast(new CacheEntry(id, data));
      return data;
    } finally {
      cacheLock.unlock();
    }
  }

  static String renderIndex() {
    return """
        <!DOCTYPE html>
        <html><head>
          <meta name=\"robots\" content=\"noindex,nofollow\">
          <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
          <script src=\"https://cdn.tailwindcss.com\"></script>
          <title>Ncdu Export Browser</title>
        </head><body class=\"p-6 bg-white text-gray-800\">
          <header class=\"mb-6\"><h1 class=\"text-2xl font-bold\">Ncdu Export Browser</h1></header>
          <main class=\"max-w-xl mx-auto\">
            <form method=\"POST\" action=\"/upload\" enctype=\"multipart/form-data\" class=\"space-y-4\">
              <div><label class=\"block font-semibold\">Select export file:</label>
              <input type=\"file\" name=\"f\" class=\"mt-1\"></div>
              <button type=\"submit\" class=\"px-4 py-2 bg-orange-600 text-white rounded\">Upload</button>
            </form>
            <p class=\"mt-4 text-sm text-gray-600\">Or place your exports into <code>exports/</code>.</p>
          </main>
        </body></html>
        """;
  }

  static String renderBrowsePage(JsonNode root, String id) {
    JsonNode dir = root.get(3);
    JsonNode info = dir.get(0);
    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html><html><head>")
        .append("<meta name=\"robots\" content=\"noindex,nofollow\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<script src=\"https://cdn.tailwindcss.com\"></script>")
        .append("<title>Browsing ").append(info.get("name").asText()).append("</title>")
        .append("</head><body class=\"p-6 bg-white text-gray-800\">")
        .append("<header class=\"mb-6\"><h1 class=\"text-2xl font-bold\">Browsing ")
        .append(info.get("name").asText()).append("</h1></header>")
        .append("<main class=\"max-w-3xl mx-auto\">")
        .append("<table class=\"min-w-full table-auto\"><thead><tr>")
        .append(
            "<th>Name</th><th class=\"text-right\">Apparent size</th><th class=\"text-right\">Disk size</th></tr></thead><tbody>");
    for (int i = 1; i < dir.size(); i++) {
      JsonNode item = dir.get(i);
      JsonNode node = item.isArray() ? item.get(0) : item;
      String name = node.get("name").asText();
      long asize = node.path("asize").asLong();
      long dsize = node.path("dsize").asLong();
      sb.append("<tr><td>")
          .append(name)
          .append("</td><td class=\"text-right\">")
          .append(asize)
          .append("</td><td class=\"text-right\">")
          .append(dsize)
          .append("</td></tr>");
    }
    sb.append("</tbody></table></main></body></html>");
    return sb.toString();
  }

  static class CacheEntry {
    final String id;
    final JsonNode data;
    long lastUsed;

    CacheEntry(String id, JsonNode data) {
      this.id = id;
      this.data = data;
      this.lastUsed = System.nanoTime();
    }
  }
}
