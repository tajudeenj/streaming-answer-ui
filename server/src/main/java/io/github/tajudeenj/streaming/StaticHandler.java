package io.github.tajudeenj.streaming;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the built web app with path-traversal protection, SPA fallback and cache headers. */
final class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "png", "image/png",
            "ico", "image/x-icon", "json", "application/json", "woff2", "font/woff2", "txt", "text/plain; charset=utf-8");

    private final Path root;

    StaticHandler(Path root) {
        Path r = root.toAbsolutePath().normalize();
        try {
            r = r.toRealPath();
        } catch (IOException ignored) {
            // directory may not exist yet (web app not built); requests will return 404 until it does
        }
        this.root = r;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            Http.error(ex, 405, "method_not_allowed", "Use GET.");
            return;
        }
        String rawPath = ex.getRequestURI().getRawPath();
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, "bad_path", "Invalid path.");
            return;
        }
        if (decoded.indexOf('\0') >= 0 || decoded.contains("\\")) {
            Http.error(ex, 400, "bad_path", "Invalid path.");
            return;
        }
        String rel = decoded.equals("/") ? "index.html" : decoded.substring(1);
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) {
            Http.error(ex, 404, "not_found", "Not found.");
            return;
        }
        boolean isAsset = rel.startsWith("assets/");
        if (!Files.isRegularFile(file)) {
            if (isAsset || rel.contains(".")) {
                Http.error(ex, 404, "not_found", "Not found.");
                return;
            }
            file = root.resolve("index.html"); // client-side route
            if (!Files.isRegularFile(file)) {
                Http.error(ex, 404, "not_built", "The web app has not been built. Run npm run build in web/.");
                return;
            }
        }
        if (!file.toRealPath().startsWith(root)) { // reject symlinks that point outside the web root
            Http.error(ex, 404, "not_found", "Not found.");
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", isAsset ? "public, max-age=31536000, immutable" : "no-cache");
        long size = Files.size(file);
        if ("HEAD".equals(method)) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, size);
        try (OutputStream out = ex.getResponseBody()) {
            Files.copy(file, out);
        }
    }
}
