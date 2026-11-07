package io.github.tajudeenj.streaming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combines rankings from different retrievers using rank positions only, so
 * BM25 scores and cosine similarities never need to be put on a common scale.
 */
public final class RrfFusion {
    public static final int DEFAULT_K = 60;

    private RrfFusion() {}

    public static List<PolicyChunk> fuse(List<List<PolicyChunk>> rankings, int k, int limit) {
        Map<String, double[]> score = new LinkedHashMap<>();
        Map<String, PolicyChunk> chunk = new LinkedHashMap<>();
        for (List<PolicyChunk> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                PolicyChunk c = ranking.get(rank);
                String key = c.key();
                chunk.putIfAbsent(key, c);
                score.computeIfAbsent(key, x -> new double[1])[0] += 1.0 / (k + rank + 1);
            }
        }
        List<String> keys = new ArrayList<>(score.keySet());
        keys.sort(Comparator.comparingDouble((String key) -> -score.get(key)[0]));
        List<PolicyChunk> out = new ArrayList<>(Math.min(limit, keys.size()));
        for (String key : keys) {
            if (out.size() >= limit) break;
            out.add(chunk.get(key));
        }
        return out;
    }
}
