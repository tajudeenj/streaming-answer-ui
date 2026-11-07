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
}
