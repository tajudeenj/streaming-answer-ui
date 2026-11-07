package io.github.tajudeenj.streaming;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies entitlement and validity rules before ranking, then trims weak matches. Returning an empty
 * list is a deliberate outcome: the caller abstains instead of letting the model answer from memory.
 */
public final class PolicyRetriever {
    private static final int MAX_PER_DOCUMENT = 2;
    private static final double RELATIVE_CUTOFF = 0.35;

    private final Bm25Index index;
    private final Clock clock;
    private final int topK;
    private final double minScore;
    private final double minCoverage;

    /**
     * @param minCoverage share of the question's terms the best passage must contain (0..1); supporting
     *                    passages need half of that. Guards against answering from a passage that only
     *                    shares a word or two with the question.
     */
    public PolicyRetriever(Bm25Index index, Clock clock, int topK, double minScore, double minCoverage) {
        this.index = index;
        this.clock = clock;
        this.topK = topK;
        this.minScore = minScore;
        this.minCoverage = minCoverage;
    }

    public int indexedChunks() { return index.size(); }

    public List<Bm25Index.Hit> retrieve(String query, String audience) {
        LocalDate today = LocalDate.now(clock);
        List<Bm25Index.Hit> ranked = index.search(query,
                c -> c.visibleTo(audience) && c.effectiveOn(today), topK * 3);
        if (ranked.isEmpty()) return List.of();
        Bm25Index.Hit best = ranked.get(0);
        if (best.score() < minScore || best.coverage() < minCoverage) return List.of();

        double floor = Math.max(minScore, ranked.get(0).score() * RELATIVE_CUTOFF);
        Map<String, Integer> perDoc = new HashMap<>();
        List<Bm25Index.Hit> out = new ArrayList<>(topK);
        for (Bm25Index.Hit h : ranked) {
            if (out.size() >= topK || h.score() < floor) break;
            if (h.coverage() < minCoverage / 2) continue;
            String key = h.chunk().docId() + "@" + h.chunk().version();
            if (perDoc.merge(key, 1, Integer::sum) > MAX_PER_DOCUMENT) continue;
            out.add(h);
        }
        return List.copyOf(out);
    }
}
