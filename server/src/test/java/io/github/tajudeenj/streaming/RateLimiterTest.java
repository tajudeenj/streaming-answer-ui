package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsBurstUpToCapacityThenBlocksPerClient() {
        RateLimiter rl = new RateLimiter(3);
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("b"));
        assertTrue(rl.retryAfterSeconds("a") >= 1);
    }
}
