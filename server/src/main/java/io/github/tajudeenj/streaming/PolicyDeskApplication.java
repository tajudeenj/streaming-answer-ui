package io.github.tajudeenj.streaming;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Policy Desk gateway: Spring Boot 4 on virtual threads, Spring AI 2 for models, embeddings and pgvector.
 * The controls (guards, entitlements, retrieval gates, streaming contract) are this application's own code.
 */
@SpringBootApplication
public class PolicyDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyDeskApplication.class, args);
    }

    @Bean
    Config config() { return Config.fromEnv(); }

    @Bean
    Metrics metrics() { return new Metrics(); }

    @Bean
    Clock clock(Config cfg) { return Clock.system(cfg.zone()); }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("sse-timer-", 0).factory());
    }

    @Bean
    RateLimiter rateLimiter(Config cfg) { return new RateLimiter(cfg.rateLimitPerMinute()); }

    @Bean
    PolicyLibrary policyLibrary(Config cfg) throws IOException { return new PolicyLibrary(PolicyLoader.load(cfg.policyDir())); }

    @Bean
    LlmProvider llmProvider(ObjectProvider<ChatModel> chatModel, Config cfg, Environment env) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) return new MockProvider();
        return new SpringAiChatProvider(model, env.getProperty("spring.ai.openai.chat.options.model", "openai"),
                cfg.upstreamTotalTimeout());
    }

    @Bean
    VectorIndexSync vectorIndexSync(PolicyLibrary library, VectorStore store, JdbcTemplate jdbc) {
        return new VectorIndexSync(library, store, jdbc);
    }

    @Bean
    HybridRetriever hybridRetriever(PolicyLibrary library, VectorStore store, VectorIndexSync sync,
                                    Clock clock, Config cfg, Metrics metrics) {
        // The reranker may be absent (disabled or download failed); the retriever then degrades to the lexical gate.
        return new HybridRetriever(library, store, sync, CrossEncoderReranker.loadOrNull(cfg), clock, cfg, metrics);
    }

    /** Embeds new passages before the service reports ready; failure leaves lexical retrieval running. */
    @Bean
    ApplicationRunner indexOnStartup(VectorIndexSync sync, Metrics metrics) {
        return args -> {
            try {
                sync.sync();
            } catch (RuntimeException e) {
                metrics.vectorFallbacks.incrementAndGet();
                org.slf4j.LoggerFactory.getLogger(PolicyDeskApplication.class)
                        .warn("vector index sync failed; serving lexical retrieval only", e);
            }
        };
    }

    @Bean
    FilterRegistrationBean<EdgeFilter> edgeFilter(Config cfg) {
        FilterRegistrationBean<EdgeFilter> reg = new FilterRegistrationBean<>(new EdgeFilter(cfg.allowedOrigin()));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
