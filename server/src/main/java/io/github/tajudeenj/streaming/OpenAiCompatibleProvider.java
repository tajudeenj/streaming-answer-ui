package io.github.tajudeenj.streaming;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Streams from any OpenAI-compatible chat-completions endpoint (OpenAI, Azure OpenAI, vLLM, LiteLLM, ...).
 * One shared HttpClient gives connection pooling and HTTP/2 multiplexing; a watchdog enforces a hard
 * total deadline, because the client's request timeout only covers the wait for response headers.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleProvider.class.getName());
    private static final int MAX_LINE_CHARS = 1 << 20;

    private final Config cfg;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final URI endpoint;

    public OpenAiCompatibleProvider(Config cfg, HttpClient http, ScheduledExecutorService scheduler) {
        this.cfg = cfg;
        this.http = http;
        this.scheduler = scheduler;
        this.endpoint = URI.create(cfg.llmEndpoint());
    }

    @Override
    public String model() { return cfg.llmModel(); }

    @Override
    public void stream(List<ChatMessage> messages, List<Bm25Index.Hit> sources, TokenSink sink) throws IOException, UpstreamException, InterruptedException {
        List<Map<String, Object>> msgs = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) msgs.add(Map.of("role", m.role(), "content", m.content()));
        String body = Json.obj(
                "model", cfg.llmModel(),
                "stream", true,
                "temperature", 0.2,
                "max_tokens", cfg.maxOutputTokens(),
                "messages", msgs);

        String auth = cfg.llmAuthHeader().equalsIgnoreCase("Authorization") ? "Bearer " + cfg.llmApiKey() : cfg.llmApiKey();
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(cfg.upstreamConnectTimeout().multipliedBy(4))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header(cfg.llmAuthHeader(), auth)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UpstreamException("The answer service could not be reached. Try again in a moment.", "connect failed", e);
        }

        try (InputStream in = resp.body()) {
            int status = resp.statusCode();
            if (status != 200) {
                in.readNBytes(2048); // drain a little so the connection can be reused; never log the body
                String msg = switch (status) {
                    case 429 -> "The answer service is busy. Try again in a few seconds.";
                    case 401, 403 -> "The answer service rejected the server's credentials. Contact the administrator.";
                    default -> "The answer service returned an error (" + status + "). Try again shortly.";
                };
                throw new UpstreamException(msg, "upstream status " + status, null);
            }

            ScheduledFuture<?> watchdog = scheduler.schedule(() -> closeQuietly(in),
                    cfg.upstreamTotalTimeout().toMillis(), TimeUnit.MILLISECONDS);
            try {
                pump(in, sink);
            } catch (ClientGoneException e) {
                throw e;
            } catch (IOException e) {
                if (watchdog.isDone()) {
                    throw new UpstreamException("The answer took too long and was stopped.", "total timeout", e);
                }
                throw new UpstreamException("The connection to the answer service dropped mid-answer.", "stream read failed", e);
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    private void pump(InputStream in, TokenSink sink) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
        String line;
        while ((line = r.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new ClientGoneException(null);
            if (line.length() > MAX_LINE_CHARS) throw new IOException("upstream line too long");
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if (data.equals("[DONE]")) return;
            String fragment = extractDelta(data);
            if (fragment != null && !fragment.isEmpty()) sink.accept(fragment);
        }
    }

    @SuppressWarnings("unchecked")
    static String extractDelta(String data) {
        try {
            Object root = Json.parse(data);
            if (!(root instanceof Map<?, ?> m)) return null;
            Object choices = m.get("choices");
            if (!(choices instanceof List<?> l) || l.isEmpty()) return null;
            if (!(l.get(0) instanceof Map<?, ?> c0)) return null;
            if (!(c0.get("delta") instanceof Map<?, ?> delta)) return null;
            return delta.get("content") instanceof String s ? s : null;
        } catch (IllegalArgumentException e) {
            LOG.fine("skipping malformed upstream chunk");
            return null;
        }
    }

    private static void closeQuietly(InputStream in) {
        try { in.close(); } catch (IOException ignored) { /* best effort */ }
    }
}
