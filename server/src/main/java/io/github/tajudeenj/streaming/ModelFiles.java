package io.github.tajudeenj.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads model artifacts once into a local cache with a size cap, atomic publish and optional
 * SHA-256 pinning. Pin the hash in production so a tampered upstream file is refused.
 */
final class ModelFiles {
    private static final long MAX_BYTES = 500L * 1024 * 1024;

    private ModelFiles() {}

    static Path fetch(URI uri, Path cacheDir, String fileName, String expectedSha256) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(fileName);
        if (Files.isRegularFile(target)) {
            if (expectedSha256.isBlank() || expectedSha256.equalsIgnoreCase(sha256(target))) return target;
            Files.delete(target); // cached file no longer matches the pinned hash
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("download failed with HTTP " + resp.statusCode() + " for " + uri.getHost());
        }
        Path tmp = Files.createTempFile(cacheDir, fileName, ".part");
        MessageDigest md = digest();
        try (InputStream in = resp.body(); OutputStream out = new DigestOutputStream(Files.newOutputStream(tmp), md)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("model file exceeds size limit");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA-256 mismatch for " + fileName);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String sha256(Path p) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
