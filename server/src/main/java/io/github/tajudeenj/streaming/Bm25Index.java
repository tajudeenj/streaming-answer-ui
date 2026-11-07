package io.github.tajudeenj.streaming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable in-memory BM25 index. Lexical retrieval is deterministic, explainable and needs no
 * embedding model, which suits regulated policy text full of exact product terms. The retriever
 * interface leaves room to add a vector index later and fuse the two rankings.
 */
public final class Bm25Index {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "is", "are", "be", "it", "if", "at",
            "by", "as", "do", "does", "can", "my", "i", "me", "we", "you", "your", "what", "how", "when", "who",
            "which", "this", "that", "with", "from", "will", "would", "should", "there", "their", "any", "about");

    /** coverage = share of distinct query terms found in the chunk (0..1), an interpretable relevance signal. */
    public record Hit(PolicyChunk chunk, double score, double coverage) {}

    private record Posting(int doc, int tf) {}

    private final List<PolicyChunk> chunks;
    private final int[] lengths;
    private final double avgLength;
    private final Map<String, List<Posting>> postings;

    public Bm25Index(List<PolicyChunk> chunks) {
        this.chunks = List.copyOf(chunks);
        this.lengths = new int[chunks.size()];
        Map<String, List<Posting>> p = new HashMap<>();
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            PolicyChunk c = chunks.get(i);
            List<String> terms = tokenize(c.title() + " " + c.section() + " " + c.text());
            lengths[i] = terms.size();
            total += terms.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String t : terms) tf.merge(t, 1, Integer::sum);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                p.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Posting(i, e.getValue()));
            }
        }
        this.avgLength = chunks.isEmpty() ? 1 : (double) total / chunks.size();
        Map<String, List<Posting>> frozen = new HashMap<>();
        p.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.postings = Map.copyOf(frozen);
    }

    public int size() { return chunks.size(); }

    /** Scores only chunks the filter allows, so filtered-out content can never influence or leak into results. */
    public List<Hit> search(String query, Predicate<PolicyChunk> allowed, int k) {
        if (chunks.isEmpty() || k <= 0) return List.of();
        boolean[] ok = new boolean[chunks.size()];
        int okCount = 0;
        for (int i = 0; i < ok.length; i++) {
            ok[i] = allowed.test(chunks.get(i));
            if (ok[i]) okCount++;
        }
        if (okCount == 0) return List.of();

        double[] score = new double[chunks.size()];
        int[] matched = new int[chunks.size()];
        int n = chunks.size();
        Set<String> queryTerms = Set.copyOf(tokenize(query));
        if (queryTerms.isEmpty()) return List.of();
        for (String term : queryTerms) {
            List<Posting> list = postings.get(term);
            if (list == null) continue;
            double idf = Math.log(1 + (n - list.size() + 0.5) / (list.size() + 0.5));
            for (Posting post : list) {
                if (!ok[post.doc]) continue;
                double norm = post.tf * (K1 + 1) / (post.tf + K1 * (1 - B + B * lengths[post.doc] / avgLength));
                score[post.doc] += idf * norm;
                matched[post.doc]++;
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -score[i]));
        List<Hit> out = new ArrayList<>(k);
        for (Integer i : order) {
            if (out.size() >= k || score[i] <= 0) break;
            out.add(new Hit(chunks.get(i), score[i], (double) matched[i] / queryTerms.size()));
        }
        return out;
    }

    static List<String> tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                String t = stem(cur.toString());
                if (t.length() > 1 && !STOP.contains(t)) out.add(t);
                cur.setLength(0);
            }
        }
        return out;
    }

    /** Light plural folding only; aggressive stemming hurts exact financial terms. */
    private static String stem(String t) {
        if (t.length() > 4 && t.endsWith("ies")) return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us")) return t.substring(0, t.length() - 1);
        return t;
    }
}
