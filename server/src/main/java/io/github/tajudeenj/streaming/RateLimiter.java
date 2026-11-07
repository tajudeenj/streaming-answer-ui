package io.github.tajudeenj.streaming;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-client token bucket with a bounded LRU key space, so a flood of unique client
 * addresses cannot grow memory without limit. Single-node only: put a shared store
 * (e.g. Redis) or the API gateway's limiter in front when running more than one replica.
 */
public final class RateLimiter {
    private static final int MAX_KEYS = 100_000;

    private final double capacity;
    private final double refillPerNano;
    private final Map<String, double[]> buckets;

    public RateLimiter(int perMinute) {
        this.capacity = perMinute;
        this.refillPerNano = perMinute / 60_000_000_000.0;
        this.buckets = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > MAX_KEYS;
            }
        };
    }

    /** @return true when the request is allowed. */
    public synchronized boolean tryAcquire(String key) {
        long now = System.nanoTime();
        double[] b = buckets.get(key); // [tokens, lastNanos]
        if (b == null) {
            buckets.put(key, new double[] {capacity - 1, now});
            return true;
        }
        b[0] = Math.min(capacity, b[0] + (now - (long) b[1]) * refillPerNano);
        b[1] = now;
        if (b[0] < 1) return false;
        b[0] -= 1;
        return true;
    }

    /** Seconds until one token is available for the key (for Retry-After). */
    public synchronized long retryAfterSeconds(String key) {
        double[] b = buckets.get(key);
        if (b == null || b[0] >= 1) return 0;
        return Math.max(1, (long) Math.ceil((1 - b[0]) / refillPerNano / 1_000_000_000.0));
    }
}
