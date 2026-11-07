package io.github.tajudeenj.streaming;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;

/** Immutable runtime configuration, read once from environment variables. */
public record Config(
        int port,
        int backlog,
        String provider,
        String llmEndpoint,
        String llmApiKey,
        String llmAuthHeader,
        String llmModel,
        int maxOutputTokens,
        int maxConcurrentStreams,
        int rateLimitPerMinute,
        int maxQuestionChars,
        int maxHistoryTurns,
        int maxBodyBytes,
        Duration upstreamConnectTimeout,
        Duration upstreamTotalTimeout,
        Duration heartbeatInterval,
        String allowedOrigin,
        Path staticDir,
        boolean trustProxy,
        Path policyDir,
        int retrievalTopK,
        double retrievalMinScore,
        double retrievalMinCoverage,
        boolean trustIdentityHeader,
        ZoneId zone) {

    public static Config fromEnv() {
        Config c = new Config(
                intEnv("PORT", 8080, 1, 65535),
                intEnv("HTTP_BACKLOG", 1024, 16, 65535),
                env("LLM_PROVIDER", "mock").toLowerCase(Locale.ROOT),
                env("LLM_ENDPOINT", "https://api.openai.com/v1/chat/completions"),
                env("LLM_API_KEY", ""),
                env("LLM_AUTH_HEADER", "Authorization"),
                env("LLM_MODEL", "gpt-4o-mini"),
                intEnv("LLM_MAX_OUTPUT_TOKENS", 800, 16, 8192),
                intEnv("MAX_CONCURRENT_STREAMS", 200, 1, 100_000),
                intEnv("RATE_LIMIT_PER_MINUTE", 20, 1, 100_000),
                intEnv("MAX_QUESTION_CHARS", 4000, 1, 100_000),
                intEnv("MAX_HISTORY_TURNS", 6, 0, 50),
                intEnv("MAX_BODY_BYTES", 64 * 1024, 1024, 4 * 1024 * 1024),
                Duration.ofSeconds(intEnv("UPSTREAM_CONNECT_TIMEOUT_SECONDS", 5, 1, 120)),
                Duration.ofSeconds(intEnv("UPSTREAM_TOTAL_TIMEOUT_SECONDS", 90, 5, 900)),
                Duration.ofSeconds(intEnv("SSE_HEARTBEAT_SECONDS", 15, 1, 300)),
                env("ALLOWED_ORIGIN", ""),
                Path.of(env("STATIC_DIR", "../web/dist")).toAbsolutePath().normalize(),
                Boolean.parseBoolean(env("TRUST_PROXY", "false")),
                Path.of(env("POLICY_DIR", "../policies")).toAbsolutePath().normalize(),
                intEnv("RETRIEVAL_TOP_K", 4, 1, 20),
                intEnv("RETRIEVAL_MIN_SCORE_X100", 150, 0, 10_000) / 100.0,
                intEnv("RETRIEVAL_MIN_COVERAGE_PCT", 50, 0, 100) / 100.0,
                Boolean.parseBoolean(env("TRUST_IDENTITY_HEADER", "false")),
                zone(env("TIME_ZONE", "Asia/Dubai")));
        c.validate();
        return c;
    }

    private void validate() {
        if (!provider.equals("mock") && !provider.equals("openai")) {
            throw new IllegalStateException("LLM_PROVIDER must be 'mock' or 'openai'");
        }
        if (provider.equals("openai")) {
            if (llmApiKey.isBlank()) throw new IllegalStateException("LLM_API_KEY is required for provider 'openai'");
            if (!llmEndpoint.startsWith("https://")) throw new IllegalStateException("LLM_ENDPOINT must use https");
        }
    }

    /** Never print the API key. */
    @Override
    public String toString() {
        return "Config[port=" + port + ", provider=" + provider + ", model=" + llmModel
                + ", maxConcurrentStreams=" + maxConcurrentStreams + ", rateLimitPerMinute=" + rateLimitPerMinute
                + ", staticDir=" + staticDir + ", trustProxy=" + trustProxy + ", policyDir=" + policyDir
                + ", retrievalTopK=" + retrievalTopK + ", trustIdentityHeader=" + trustIdentityHeader + ", zone=" + zone + "]";
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
