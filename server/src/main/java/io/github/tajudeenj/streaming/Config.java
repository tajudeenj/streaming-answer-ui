package io.github.tajudeenj.streaming;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Gateway policy settings, read once from environment variables and validated at startup.
 * Infrastructure settings (port, database, model provider) live in application.yml.
 */
public record Config(
        String provider,
        int maxConcurrentStreams,
        int rateLimitPerMinute,
        int maxQuestionChars,
        int maxHistoryTurns,
        int maxBodyBytes,
        Duration upstreamTotalTimeout,
        Duration heartbeatInterval,
        String allowedOrigin,
        boolean trustProxy,
        Path policyDir,
        int retrievalTopK,
        int candidatePool,
        double retrievalMinScore,
        double retrievalMinCoverage,
        boolean trustIdentityHeader,
        ZoneId zone,
        boolean rerankerEnabled,
        URI rerankerModelUri,
        URI rerankerTokenizerUri,
        String rerankerModelSha256,
        double rerankerMinScore,
        Path modelCacheDir) {

    public static Config fromEnv() {
        return new Config(
                env("LLM_PROVIDER", "mock").toLowerCase(Locale.ROOT),
                intEnv("MAX_CONCURRENT_STREAMS", 200, 1, 100_000),
                intEnv("RATE_LIMIT_PER_MINUTE", 20, 1, 100_000),
                intEnv("MAX_QUESTION_CHARS", 4000, 1, 100_000),
                intEnv("MAX_HISTORY_TURNS", 6, 0, 50),
                intEnv("MAX_BODY_BYTES", 64 * 1024, 1024, 4 * 1024 * 1024),
                Duration.ofSeconds(intEnv("UPSTREAM_TOTAL_TIMEOUT_SECONDS", 90, 5, 900)),
                Duration.ofSeconds(intEnv("SSE_HEARTBEAT_SECONDS", 15, 1, 300)),
                env("ALLOWED_ORIGIN", ""),
                Boolean.parseBoolean(env("TRUST_PROXY", "false")),
                Path.of(env("POLICY_DIR", "../policies")).toAbsolutePath().normalize(),
                intEnv("RETRIEVAL_TOP_K", 4, 1, 20),
                intEnv("RETRIEVAL_CANDIDATE_POOL", 20, 4, 200),
                intEnv("RETRIEVAL_MIN_SCORE_X100", 150, 0, 10_000) / 100.0,
                intEnv("RETRIEVAL_MIN_COVERAGE_PCT", 50, 0, 100) / 100.0,
                Boolean.parseBoolean(env("TRUST_IDENTITY_HEADER", "false")),
                zone(env("TIME_ZONE", "Asia/Dubai")),
                Boolean.parseBoolean(env("RERANKER_ENABLED", "true")),
                uri(env("RERANKER_MODEL_URI", "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/onnx/model.onnx")),
                uri(env("RERANKER_TOKENIZER_URI", "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/tokenizer.json")),
                env("RERANKER_MODEL_SHA256", ""),
                intEnv("RERANKER_MIN_SCORE_X100", 0, -2_000, 2_000) / 100.0,
                Path.of(env("MODEL_CACHE_DIR", System.getProperty("user.home") + "/.cache/policy-desk")).toAbsolutePath().normalize());
    }

    private static URI uri(String v) {
        URI u = URI.create(v);
        if (!"https".equals(u.getScheme())) throw new IllegalStateException("model URIs must use https: " + v);
        return u;
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
            throw new IllegalStateException("TIME_ZONE is not a valid zone id", e);
        }
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int intEnv(String name, int def, int min, int max) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < min || n > max) throw new IllegalStateException(name + " must be between " + min + " and " + max);
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer", e);
        }
    }
}
