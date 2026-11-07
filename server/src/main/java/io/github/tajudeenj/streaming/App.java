package io.github.tajudeenj.streaming;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Entry point. Pure JDK 21: no third-party runtime dependencies, so there is no transitive CVE surface
 * and the jar runs on locked-down servers where extra tooling cannot be installed.
 * Every request runs on its own virtual thread, so thousands of slow SSE streams cost little memory.
 */
public final class App {
    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private App() {}

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tFT%1$tT.%1$tLZ %4$s %3$s %5$s%6$s%n");
        Config cfg = Config.fromEnv();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("sse-timer-", 0).factory());
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(cfg.upstreamConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        LlmProvider provider = cfg.provider().equals("openai")
                ? new OpenAiCompatibleProvider(cfg, http, scheduler)
                : new MockProvider();

        PolicyLoader.Result library = PolicyLoader.load(cfg.policyDir());
        PolicyRetriever retriever = new PolicyRetriever(new Bm25Index(library.chunks()), Clock.system(cfg.zone()),
                cfg.retrievalTopK(), cfg.retrievalMinScore(), cfg.retrievalMinCoverage());

        Metrics metrics = new Metrics();
        StreamHandler stream = new StreamHandler(cfg, provider, retriever, new RateLimiter(cfg.rateLimitPerMinute()), scheduler, metrics);
        Filter common = commonFilter(cfg);

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port()), cfg.backlog());
        server.setExecutor(requests);
        server.createContext("/api/answer/stream", stream).getFilters().add(common);
        server.createContext("/api/health", ex -> Http.json(ex, 200, Json.obj(
                "status", "up",
                "provider", provider.model(),
                "activeStreams", stream.activeStreams(),
                "streamsStarted", metrics.streamsStarted.get(),
                "completed", metrics.completed.get(),
                "cancelled", metrics.cancelled.get(),
                "upstreamErrors", metrics.upstreamErrors.get(),
                "serverErrors", metrics.serverErrors.get(),
                "rejectedRateLimit", metrics.rejectedRateLimit.get(),
                "rejectedCapacity", metrics.rejectedCapacity.get(),
                "rejectedInjection", metrics.rejectedInjection.get(),
                "abstained", metrics.abstained.get(),
                "policyDocuments", library.documents(),
                "policyChunks", library.chunks().size(),
                "quarantinedChunks", library.quarantined(),
                "rejectedPolicyFiles", library.rejectedFiles()))).getFilters().add(common);
        server.createContext("/", new StaticHandler(cfg.staticDir())).getFilters().add(common);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down: draining for up to 10s");
            server.stop(10);
            requests.shutdown();
            scheduler.shutdownNow();
            try {
                requests.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown"));

        server.start();
        LOG.info("listening on :" + cfg.port() + " " + cfg);
    }

    /** Request id, security headers, CORS (only for an explicitly configured origin) and last-resort error handling. */
    private static Filter commonFilter(Config cfg) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange ex, Chain chain) throws IOException {
                String incoming = ex.getRequestHeaders().getFirst(Http.REQUEST_ID);
                String rid = incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
                ex.getResponseHeaders().set(Http.REQUEST_ID, rid);
                Http.securityHeaders(ex);

                String origin = ex.getRequestHeaders().getFirst("Origin");
                boolean corsAllowed = !cfg.allowedOrigin().isEmpty() && cfg.allowedOrigin().equals(origin);
                if (corsAllowed) {
                    ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                    ex.getResponseHeaders().set("Vary", "Origin");
                    ex.getResponseHeaders().set("Access-Control-Expose-Headers", Http.REQUEST_ID + ", Retry-After");
                }
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    if (corsAllowed) {
                        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST");
                        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, " + Http.REQUEST_ID);
                        ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
                    }
                    ex.sendResponseHeaders(corsAllowed ? 204 : 403, -1);
                    ex.close();
                    return;
                }
                try {
                    chain.doFilter(ex);
                } catch (IOException e) {
                    throw e;
                } catch (RuntimeException e) {
                    LOG.warning("rid=" + rid + " unhandled " + e.getClass().getSimpleName());
                    try {
                        Http.error(ex, 500, "internal", "Unexpected server error.");
                    } catch (IOException | IllegalStateException ignored) { /* headers already sent */ }
                } finally {
                    ex.close();
                }
            }

            @Override
            public String description() { return "common"; }
        };
    }
}
