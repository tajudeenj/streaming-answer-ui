package io.github.tajudeenj.streaming;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Hybrid retrieval: entitlement and validity filters first, then lexical (BM25) and semantic (pgvector)
 * rankings fused with RRF, then a cross-encoder decides which passages actually answer the question.
 * Every stage degrades safely: vector store down means lexical only; reranker down means the lexical
 * relevance gate; nothing relevant means abstain.
 */
public final class HybridRetriever implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HybridRetriever.class.getName());
    private static final int RERANK_CANDIDATES = 12;
    private static final int MAX_PER_DOCUMENT = 2;

    private final PolicyLibrary library;
    private final VectorStore vectorStore;
    private final VectorIndexSync sync;
    private final CrossEncoderReranker reranker;
    private final PolicyRetriever lexicalFallback;
    private final Clock clock;
    private final Config cfg;
    private final Metrics metrics;

    public HybridRetriever(PolicyLibrary library, VectorStore vectorStore, VectorIndexSync sync,
                           CrossEncoderReranker reranker, Clock clock, Config cfg, Metrics metrics) {
        this.library = library;
        this.vectorStore = vectorStore;
        this.sync = sync;
        this.reranker = reranker;
        this.clock = clock;
        this.cfg = cfg;
        this.metrics = metrics;
        this.lexicalFallback = new PolicyRetriever(library.lexical(), clock, cfg.retrievalTopK(),
                cfg.retrievalMinScore(), cfg.retrievalMinCoverage());
    }

    public boolean rerankerActive() { return reranker != null; }

    @Override
    public void close() throws Exception {
        if (reranker != null) reranker.close();
    }

    public List<Source> retrieve(String query, String audience) {
        LocalDate today = LocalDate.now(clock);
        Predicate<PolicyChunk> allowed = c -> c.visibleTo(audience) && c.effectiveOn(today);

        List<PolicyChunk> lexical = new ArrayList<>();
        for (Bm25Index.Hit h : library.lexical().search(query, allowed, cfg.candidatePool())) lexical.add(h.chunk());
        List<PolicyChunk> dense = semantic(query, audience, today, allowed);
        List<PolicyChunk> fused = RrfFusion.fuse(List.of(lexical, dense), RrfFusion.DEFAULT_K, RERANK_CANDIDATES);
        if (fused.isEmpty()) return List.of();

        if (reranker == null) return fallback(query, audience);
        float[] scores;
        try {
            List<String> texts = new ArrayList<>(fused.size());
            for (PolicyChunk c : fused) texts.add(c.title() + ". " + c.section() + ". " + c.text());
            scores = reranker.score(query, texts);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("reranker failed, using lexical gate: " + e.getClass().getSimpleName());
            scores = null;
        }
        if (scores == null) {
            metrics.rerankerFallbacks.incrementAndGet();
            return fallback(query, audience);
        }

        List<Source> ranked = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) {
            if (scores[i] >= cfg.rerankerMinScore()) ranked.add(new Source(fused.get(i), scores[i]));
        }
        ranked.sort((a, b) -> Double.compare(b.relevance(), a.relevance()));
        Map<String, Integer> perDoc = new HashMap<>();
        List<Source> out = new ArrayList<>(cfg.retrievalTopK());
        for (Source s : ranked) {
            if (out.size() >= cfg.retrievalTopK()) break;
            String doc = s.chunk().docId() + "@" + s.chunk().version();
            if (perDoc.merge(doc, 1, Integer::sum) > MAX_PER_DOCUMENT) continue;
            out.add(s);
        }
        return List.copyOf(out);
    }

    private List<PolicyChunk> semantic(String query, String audience, LocalDate today, Predicate<PolicyChunk> allowed) {
        if (!sync.ready()) return List.of();
        long day = today.toEpochDay();
        String filter = "aud_" + audience + " == 'yes' && from_day <= " + day + " && to_day > " + day;
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query).topK(cfg.candidatePool()).similarityThresholdAll().filterExpression(filter).build());
            List<PolicyChunk> out = new ArrayList<>(docs.size());
            for (Document d : docs) {
                Object key = d.getMetadata().get("chunk_key");
                PolicyChunk c = key == null ? null : library.byKey(key.toString());
                // Defence in depth: re-check entitlement and validity against the authoritative library.
                if (c != null && allowed.test(c)) out.add(c);
            }
            return out;
        } catch (RuntimeException e) {
            metrics.vectorFallbacks.incrementAndGet();
            LOG.warning("vector search failed, continuing with lexical results: " + e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<Source> fallback(String query, String audience) {
        List<Source> out = new ArrayList<>();
        for (Bm25Index.Hit h : lexicalFallback.retrieve(query, audience)) out.add(new Source(h.chunk(), h.score()));
        return out;
    }
}
