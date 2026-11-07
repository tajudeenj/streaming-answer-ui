package io.github.tajudeenj.streaming;

import java.time.LocalDate;
import java.util.Set;

/**
 * One retrievable passage of a policy document, carrying the metadata needed for
 * access control (audiences), validity (effective window) and provenance (version, hash).
 */
public record PolicyChunk(
        String docId,
        String title,
        String version,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Set<String> audiences,
        String section,
        String text,
        String docSha256) {

    /** True when the chunk is valid on the given day: from is inclusive, to is exclusive. */
    public boolean effectiveOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || day.isBefore(effectiveTo));
    }

    public boolean visibleTo(String audience) {
        return audiences.contains(audience);
    }

    /** Stable identity for this exact passage text in this exact document version. */
    public String key() {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((docId + "@" + version + "#" + section + "#" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return docId + "@" + version + "#" + java.util.HexFormat.of().formatHex(d, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
