package io.github.tajudeenj.streaming;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Operational counters for dashboards and alerting; liveness and readiness probes are under /actuator/health. */
@RestController
public class HealthController {
    private final Metrics metrics;
    private final PolicyLibrary library;
    private final LlmProvider provider;
    private final VectorIndexSync sync;
    private final HybridRetriever retriever;
    private final AnswerController answers;

    public HealthController(Metrics metrics, PolicyLibrary library, LlmProvider provider, VectorIndexSync sync,
                            HybridRetriever retriever, AnswerController answers) {
        this.metrics = metrics;
        this.library = library;
        this.provider = provider;
        this.sync = sync;
        this.retriever = retriever;
        this.answers = answers;
    }

    @GetMapping("/api/health")
    public void health(HttpServletResponse res) throws IOException {
        HttpIo.json(res, 200, Json.obj(
                "status", "up",
                "provider", provider.model(),
                "vectorIndexReady", sync.ready(),
                "rerankerActive", retriever.rerankerActive(),
                "activeStreams", answers.activeStreams(),
                "streamsStarted", metrics.streamsStarted.get(),
                "completed", metrics.completed.get(),
                "cancelled", metrics.cancelled.get(),
                "upstreamErrors", metrics.upstreamErrors.get(),
                "serverErrors", metrics.serverErrors.get(),
                "rejectedRateLimit", metrics.rejectedRateLimit.get(),
                "rejectedCapacity", metrics.rejectedCapacity.get(),
                "rejectedInjection", metrics.rejectedInjection.get(),
                "abstained", metrics.abstained.get(),
                "rerankerFallbacks", metrics.rerankerFallbacks.get(),
                "vectorFallbacks", metrics.vectorFallbacks.get(),
                "policyDocuments", library.documents(),
                "policyChunks", library.chunks().size(),
                "quarantinedChunks", library.quarantined(),
                "rejectedPolicyFiles", library.rejectedFiles()));
    }
}
