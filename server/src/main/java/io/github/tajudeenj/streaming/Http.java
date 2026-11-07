package io.github.tajudeenj.streaming;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shared HTTP helpers: security headers, bounded body reads, JSON replies, client identity. */
final class Http {
    static final String REQUEST_ID = "X-Request-Id";

    private Http() {}

    static void securityHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                + "font-src 'self' https://fonts.gstatic.com; connect-src 'self'; img-src 'self' data:; "
                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    }

    /** Reads at most {@code limit} bytes; returns null when the body is larger. */
    static byte[] readBody(HttpExchange ex, int limit) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try {
                if (Long.parseLong(len.trim()) > limit) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > limit) return null;
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        json(ex, status, Json.obj("error", code, "message", message,
                "requestId", ex.getResponseHeaders().getFirst(REQUEST_ID)));
    }

    static String clientKey(HttpExchange ex, boolean trustProxy) {
        if (trustProxy) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // The right-most entry is the address our own proxy saw; earlier entries are client-controlled.
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty() && last.length() <= 64) return last;
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }
}
