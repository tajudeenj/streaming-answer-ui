package io.github.tajudeenj.streaming;

import java.util.concurrent.atomic.AtomicLong;

/** Lock-free counters exposed at /api/health for dashboards and alerting. */
final class Metrics {
    final AtomicLong streamsStarted = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong cancelled = new AtomicLong();
    final AtomicLong upstreamErrors = new AtomicLong();
    final AtomicLong serverErrors = new AtomicLong();
    final AtomicLong rejectedRateLimit = new AtomicLong();
    final AtomicLong rejectedCapacity = new AtomicLong();
    final AtomicLong rejectedInjection = new AtomicLong();

    void record(String outcome) {
        switch (outcome) {
            case "ok" -> completed.incrementAndGet();
            case "client_cancelled" -> cancelled.incrementAndGet();
            case "upstream_error" -> upstreamErrors.incrementAndGet();
            default -> serverErrors.incrementAndGet();
        }
    }
}
