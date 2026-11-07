package io.github.tajudeenj.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Servlet helpers: bounded body reads, JSON error replies and client identity for rate limiting. */
final class HttpIo {
    private HttpIo() {}

    /** Reads at most {@code limit} bytes; returns null when the body is larger. */
    static byte[] readBody(HttpServletRequest req, int limit) throws IOException {
        long declared = req.getContentLengthLong();
        if (declared > limit) return null;
        try (InputStream in = req.getInputStream()) {
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

    static void json(HttpServletResponse res, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.setContentLength(bytes.length);
        try (OutputStream out = res.getOutputStream()) {
            out.write(bytes);
        }
    }

    static void error(HttpServletResponse res, String requestId, int status, String code, String message) throws IOException {
        json(res, status, Json.obj("error", code, "message", message, "requestId", requestId));
    }

    static String clientKey(HttpServletRequest req, boolean trustProxy) {
        if (trustProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // The right-most entry is the address our own proxy saw; earlier entries are client-controlled.
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty() && last.length() <= 64) return last;
            }
        }
        return req.getRemoteAddr();
    }
}
