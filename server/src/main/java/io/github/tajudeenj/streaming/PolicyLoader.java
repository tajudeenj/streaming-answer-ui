package io.github.tajudeenj.streaming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads Markdown policy documents with front matter, splits them into section chunks and screens every
 * chunk for indirect prompt injection. Unsafe chunks are quarantined at ingestion, so they can never be
 * retrieved, and the operator sees them in logs and health metrics.
 */
public final class PolicyLoader {
    private static final Logger LOG = Logger.getLogger(PolicyLoader.class.getName());
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_FILES = 10_000;
    private static final int MAX_CHUNK_CHARS = 1_200;
    private static final Set<String> KNOWN_AUDIENCES = Set.of("customer", "staff");
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public record Result(List<PolicyChunk> chunks, int documents, int quarantined, int rejectedFiles) {}

    private PolicyLoader() {}

    public static Result load(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            LOG.warning("policy directory not found: " + dir + " (every question will be answered with an abstention)");
            return new Result(List.of(), 0, 0, 0);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".md") && !n.equalsIgnoreCase("README.md") && !n.startsWith("_");
            }).sorted().limit(MAX_FILES + 1L).toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("too many policy files (max " + MAX_FILES + ")");

        List<PolicyChunk> chunks = new ArrayList<>();
        Set<String> seenVersions = new HashSet<>();
        int docs = 0, quarantined = 0, rejected = 0;
        for (Path file : files) {
            try {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IllegalArgumentException("not a regular file");
                if (Files.size(file) > MAX_FILE_BYTES) throw new IllegalArgumentException("file larger than 1 MB");
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                ParsedDoc d = parse(raw);
                if (!seenVersions.add(d.id + "@" + d.version)) throw new IllegalArgumentException("duplicate id and version " + d.id + "@" + d.version);
                String sha = sha256(raw);
                docs++;
                for (Section sec : d.sections) {
                    for (String text : split(sec.body)) {
                        Optional<String> reason = RetrievedContentGuard.inspect(text);
                        if (reason.isPresent()) {
                            quarantined++;
                            LOG.warning("quarantined chunk doc=" + d.id + "@" + d.version + " section=\"" + sec.title + "\" reason=" + reason.get());
                            continue;
                        }
                        chunks.add(new PolicyChunk(d.id, d.title, d.version, d.from, d.to, d.audiences, sec.title,
                                InputGuard.sanitize(text), sha));
                    }
                }
            } catch (IllegalArgumentException e) {
                rejected++;
                LOG.warning("rejected policy file " + file.getFileName() + ": " + e.getMessage());
            }
        }
        LOG.info("policy library loaded: documents=" + docs + " chunks=" + chunks.size()
                + " quarantined=" + quarantined + " rejectedFiles=" + rejected);
        return new Result(List.copyOf(chunks), docs, quarantined, rejected);
    }

    private record Section(String title, String body) {}

    private record ParsedDoc(String id, String title, String version, LocalDate from, LocalDate to,
                             Set<String> audiences, List<Section> sections) {}

    static ParsedDoc parse(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("missing front matter");
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("unterminated front matter");

        Map<String, String> meta = new HashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("bad front matter line");
            meta.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        String id = require(meta, "id");
        if (!SAFE_ID.matcher(id).matches()) throw new IllegalArgumentException("id must be lowercase letters, digits and hyphens");
        String title = require(meta, "title");
        String version = require(meta, "version");
        LocalDate from = date(require(meta, "effective_from"), "effective_from");
        String toRaw = meta.getOrDefault("effective_to", "");
        LocalDate to = toRaw.isEmpty() ? null : date(toRaw, "effective_to");
        if (to != null && !to.isAfter(from)) throw new IllegalArgumentException("effective_to must be after effective_from");

        Set<String> audiences = new LinkedHashSet<>();
        for (String a : require(meta, "audience").split(",")) {
            String v = a.trim().toLowerCase(Locale.ROOT);
            if (!KNOWN_AUDIENCES.contains(v)) throw new IllegalArgumentException("unknown audience '" + v + "'");
            audiences.add(v);
        }

        List<Section> sections = new ArrayList<>();
        String current = "Overview";
        StringBuilder body = new StringBuilder();
        for (String line : text.substring(end + 5).split("\n")) {
            if (line.startsWith("## ")) {
                if (!body.toString().isBlank()) sections.add(new Section(current, body.toString().strip()));
                current = line.substring(3).strip();
                body.setLength(0);
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank()) sections.add(new Section(current, body.toString().strip()));
        if (sections.isEmpty()) throw new IllegalArgumentException("document has no content");
        return new ParsedDoc(id, title, version, from, to, Set.copyOf(audiences), sections);
    }

    /** Keeps sections whole when small; otherwise packs paragraphs into chunks up to the size limit. */
    static List<String> split(String body) {
        if (body.length() <= MAX_CHUNK_CHARS) return List.of(body);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\n\\s*\n")) {
            if (cur.length() > 0 && cur.length() + para.length() + 2 > MAX_CHUNK_CHARS) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            if (para.length() > MAX_CHUNK_CHARS) {
                for (int i = 0; i < para.length(); i += MAX_CHUNK_CHARS) {
                    out.add(para.substring(i, Math.min(para.length(), i + MAX_CHUNK_CHARS)).strip());
                }
            } else {
                cur.append(para).append("\n\n");
            }
        }
        if (cur.length() > 0) out.add(cur.toString().strip());
        return out;
    }

    private static String require(Map<String, String> meta, String key) {
        String v = meta.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing '" + key + "'");
        return v;
    }

    private static LocalDate date(String v, String field) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
