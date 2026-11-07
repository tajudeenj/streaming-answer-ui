package io.github.tajudeenj.streaming;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Keeps the vector index identical to the screened policy library. Each passage gets a deterministic id
 * derived from its content and version, so restarts embed only new passages and delete retired ones.
 * Quarantined passages never reach the index because they never reach the library.
 */
public final class VectorIndexSync {
    private static final Logger LOG = Logger.getLogger(VectorIndexSync.class.getName());
    private static final int BATCH = 64;
    static final long OPEN_ENDED_DAY = 9_999_999L;

    private final PolicyLibrary library;
    private final VectorStore store;
    private final JdbcTemplate jdbc;
    private volatile boolean ready;

    public VectorIndexSync(PolicyLibrary library, VectorStore store, JdbcTemplate jdbc) {
        this.library = library;
        this.store = store;
        this.jdbc = jdbc;
    }

    public boolean ready() { return ready; }

    public void sync() {
        Map<String, Document> wanted = new LinkedHashMap<>();
        for (PolicyChunk c : library.chunks()) {
            String id = UUID.nameUUIDFromBytes(c.key().getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chunk_key", c.key());
            meta.put("doc_id", c.docId());
            meta.put("version", c.version());
            meta.put("aud_customer", c.visibleTo("customer") ? "yes" : "no");
            meta.put("aud_staff", c.visibleTo("staff") ? "yes" : "no");
            meta.put("from_day", c.effectiveFrom().toEpochDay());
            meta.put("to_day", c.effectiveTo() == null ? OPEN_ENDED_DAY : c.effectiveTo().toEpochDay());
            wanted.put(id, new Document(id, c.title() + ". " + c.section() + ". " + c.text(), meta));
        }
        Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT id::text FROM public.vector_store", String.class));

        List<Document> toAdd = new ArrayList<>();
        for (Map.Entry<String, Document> e : wanted.entrySet()) if (!existing.contains(e.getKey())) toAdd.add(e.getValue());
        List<String> toDelete = new ArrayList<>();
        for (String id : existing) if (!wanted.containsKey(id)) toDelete.add(id);

        for (int i = 0; i < toAdd.size(); i += BATCH) store.add(toAdd.subList(i, Math.min(toAdd.size(), i + BATCH)));
        if (!toDelete.isEmpty()) store.delete(toDelete);
        ready = true;
        LOG.info("vector index synced: passages=" + wanted.size() + " added=" + toAdd.size() + " removed=" + toDelete.size());
    }
}
