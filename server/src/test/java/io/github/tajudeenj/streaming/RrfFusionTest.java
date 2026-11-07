package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RrfFusionTest {

    private static PolicyChunk c(String id) {
        return new PolicyChunk(id, id, "1", LocalDate.of(2026, 1, 1), null, Set.of("customer"), "S", "text " + id, "0".repeat(64));
    }

    @Test
    void rewardsAgreementBetweenRetrievers() {
        PolicyChunk a = c("a"), b = c("b"), x = c("x"), y = c("y");
        // b is second in both rankings; a and x each top only one ranking.
        List<PolicyChunk> fused = RrfFusion.fuse(List.of(List.of(a, b, y), List.of(x, b)), RrfFusion.DEFAULT_K, 10);
        assertEquals("b", fused.get(0).docId());
        assertEquals(4, fused.size());
    }

    @Test
    void respectsTheLimit() {
        assertEquals(1, RrfFusion.fuse(List.of(List.of(c("a"), c("b"))), RrfFusion.DEFAULT_K, 1).size());
    }
}
