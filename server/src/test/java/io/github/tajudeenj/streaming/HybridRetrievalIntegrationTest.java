package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end retrieval against a real pgvector database, local embeddings and the cross-encoder, using the
 * sample policy library. Skipped automatically when Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class HybridRetrievalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() { return Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneId.of("Asia/Dubai")); }
    }

    @Autowired HybridRetriever retriever;
    @Autowired VectorIndexSync sync;
    @LocalServerPort int port;

    private List<Source> ask(String q, String audience) {
        List<Source> out = retriever.retrieve(q, audience);
        // Surfaced as CI annotations so relevance thresholds can be calibrated from real scores.
        StringBuilder b = new StringBuilder("::notice title=retrieval::[" + audience + "] " + q + " => ");
        if (out.isEmpty()) b.append("ABSTAIN");
        for (Source s : out) b.append(s.chunk().docId()).append('@').append(s.chunk().version()).append('/')
                .append(s.chunk().section()).append(String.format(" (%.2f); ", s.relevance()));
        System.out.println(b);
        return out;
    }

    @Test
    void indexesTheScreenedLibrary() {
        assertTrue(sync.ready());
        assertTrue(retriever.rerankerActive(), "cross-encoder should load in CI");
    }

    @Test
    void findsParaphrasedQuestionsThatShareNoKeywords() {
        List<Source> s = ask("Can I pay off my Murabaha finance before the end of the term?", "customer");
        assertFalse(s.isEmpty());
        assertEquals("murabaha-terms", s.get(0).chunk().docId());
        assertEquals("2.0", s.get(0).chunk().version());
        assertEquals("Early settlement", s.get(0).chunk().section());
    }

    @Test
    void neverCitesExpiredVersions() {
        for (Source s : ask("Is there a rebate for early settlement of Murabaha?", "customer")) {
            assertFalse(s.chunk().docId().equals("murabaha-terms") && s.chunk().version().equals("1.0"));
        }
    }

    @Test
    void enforcesEntitlements() {
        String q = "Who approves profit margin exceptions above 50 basis points?";
        for (Source s : ask(q, "customer")) assertFalse(s.chunk().docId().equals("staff-pricing-exceptions"));
        assertEquals("staff-pricing-exceptions", ask(q, "staff").get(0).chunk().docId());
    }

    @Test
    void ignoresDocumentsNotYetEffective() {
        for (Source s : ask("Is the monthly account maintenance fee waived for salary transfer customers?", "customer")) {
            assertFalse(s.chunk().docId().equals("fees-schedule"));
        }
    }

    @Test
    void neverRetrievesQuarantinedPassages() {
        for (Source s : ask("Is card replacement free if I lose my card?", "customer")) {
            assertFalse(s.chunk().section().equals("Card replacement"));
        }
    }

    @Test
    void abstainsOnOffTopicQuestions() {
        assertTrue(ask("What will the weather be in Dubai tomorrow?", "customer").isEmpty());
    }

    @Test
    void streamsACitedAnswerOverHttp() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/answer/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"Does rent stop if my Ijarah home is destroyed?\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(res.body().contains("event: meta"));
        assertTrue(res.body().contains("ijarah-home-finance"));
        assertTrue(res.body().contains("[S1]"));
        assertTrue(res.body().contains("event: done"));
    }
}
