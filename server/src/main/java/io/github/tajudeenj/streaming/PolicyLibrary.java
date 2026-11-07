package io.github.tajudeenj.streaming;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The screened, immutable policy library loaded at startup, with its lexical index and key lookup. */
public final class PolicyLibrary {
    private final PolicyLoader.Result loaded;
    private final Bm25Index lexical;
    private final Map<String, PolicyChunk> byKey;

    public PolicyLibrary(PolicyLoader.Result loaded) {
        this.loaded = loaded;
        this.lexical = new Bm25Index(loaded.chunks());
        Map<String, PolicyChunk> m = new HashMap<>();
        for (PolicyChunk c : loaded.chunks()) m.put(c.key(), c);
        this.byKey = Map.copyOf(m);
    }

    public List<PolicyChunk> chunks() { return loaded.chunks(); }
    public Bm25Index lexical() { return lexical; }
    public PolicyChunk byKey(String key) { return byKey.get(key); }
    public int documents() { return loaded.documents(); }
    public int quarantined() { return loaded.quarantined(); }
    public int rejectedFiles() { return loaded.rejectedFiles(); }
}
