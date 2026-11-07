package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRetrieverTest {

    private static PolicyChunk chunk(String id, String version, String from, String to, Set<String> aud, String text) {
        return new PolicyChunk(id, id, version, LocalDate.parse(from), to == null ? null : LocalDate.parse(to),
                aud, "Section", text, "0".repeat(64));
    }

    private static final List<PolicyChunk> LIBRARY = List.of(
            chunk("murabaha", "1.0", "2024-01-01", "2026-01-01", Set.of("customer"), "Early settlement rebates are not offered."),
            chunk("murabaha", "2.0", "2026-01-01", null, Set.of("customer"), "Early settlement may earn a rebate on unearned profit."),
            chunk("pricing", "3.1", "2026-01-01", null, Set.of("staff"), "Profit margin exceptions need credit head approval."),
            chunk("fees", "2027", "2027-01-01", null, Set.of("customer"), "Maintenance fee waived for salary transfer customers."));

    private static PolicyRetriever retriever(String day) {
        Clock clock = Clock.fixed(Instant.parse(day + "T08:00:00Z"), ZoneOffset.UTC);
        return new PolicyRetriever(new Bm25Index(LIBRARY), clock, 4, 0.1, 0.5);
    }

    @Test
    void citesOnlyTheVersionInEffect() {
        List<Bm25Index.Hit> hits = retriever("2026-11-07").retrieve("early settlement rebate", "customer");
        assertEquals(1, hits.size());
        assertEquals("2.0", hits.get(0).chunk().version());
        assertEquals("1.0", retriever("2025-06-01").retrieve("early settlement rebate", "customer").get(0).chunk().version());
    }

    @Test
    void neverReturnsContentOutsideTheCallersAudience() {
        assertTrue(retriever("2026-11-07").retrieve("profit margin exceptions approval", "customer").isEmpty());
        assertEquals("pricing", retriever("2026-11-07").retrieve("profit margin exceptions approval", "staff").get(0).chunk().docId());
    }

    @Test
    void ignoresDocumentsNotYetEffective() {
        assertTrue(retriever("2026-11-07").retrieve("maintenance fee waived salary transfer", "customer").isEmpty());
        assertEquals(1, retriever("2027-01-02").retrieve("maintenance fee waived salary transfer", "customer").size());
    }

    @Test
    void abstainsWhenTheQuestionBarelyOverlapsAnyPassage() {
        assertTrue(retriever("2026-11-07").retrieve("weather forecast tomorrow profit", "customer").isEmpty());
    }

    @Test
    void tokenizerFoldsPluralsAndDropsStopWords() {
        assertEquals(List.of("policy", "rebate", "card"), Bm25Index.tokenize("The policies, rebates and cards"));
    }
}
