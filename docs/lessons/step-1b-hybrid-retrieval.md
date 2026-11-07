# Step 1b: Hybrid retrieval on the current Java AI stack

## The problem

Step 1 retrieved with BM25 only. Keyword search is precise on exact terms (Murabaha, Ijarah, clause names)
but blind to meaning: *"Can I pay off my Murabaha finance before the end of the term?"* shares no keywords
with the policy's *"settle early"*, so the assistant abstained even though the answer exists. The 2026
production default is hybrid retrieval with a reranker, and reviewers expect the mainstream Java AI stack.

## The design

```
filter (audience, effective date)          ── applied to both retrievers, before ranking
  ├─ BM25 over the in-memory library        ── exact terms
  └─ pgvector similarity (local MiniLM)     ── meaning; SQL metadata filter, re-checked in Java
        ▼
  Reciprocal Rank Fusion (k = 60)           ── combines ranks, no score calibration needed
        ▼
  cross-encoder rerank (local ONNX)         ── reads question + passage together
        ▼
  relevance gate ─► sources ─► model        ── nothing passes: abstain
```

| Decision | Why |
|---|---|
| Spring Boot 4.1 + Spring AI 2.0 | Current GA lines (Spring AI 2.0 GA June 2026). Portable model and vector-store abstractions; the controls stay our own code. |
| pgvector | Most banks already run PostgreSQL. One familiar database, HNSW index, SQL metadata filters, standard backup and DR. |
| Local embeddings and reranker (ONNX) | Questions and policy text never leave the server to build or search the index. No per-query cost. |
| RRF instead of score blending | BM25 scores and cosine similarities live on different scales; rank fusion avoids fragile normalisation. |
| Cross-encoder as the relevance gate | A bi-encoder or BM25 score says "similar"; a cross-encoder judges "answers the question". It replaces the coverage heuristic from step 1. |
| Entitlement filter in SQL **and** in Java | Defence in depth: a metadata bug cannot leak a staff passage to a customer. |
| Deterministic passage ids, incremental sync | Restarts embed only new passages and delete retired ones; quarantined text never reaches the vector store. |
| Every stage degrades | Vector store down: lexical only. Reranker down or saturated: lexical gate. Nothing relevant: abstain. Each fallback is counted. |
| Pinned model hash and internal mirror (production) | Treat model files like any other third-party binary in the supply chain. |

## Evidence from CI (real pgvector, real models)

| Question | Audience | Result |
|---|---|---|
| Can I pay off my Murabaha finance before the end of the term? | customer | Retrieves *Early settlement* (2.42) and *Late payment* (2.98). Step 1 abstained here. |
| Is there a rebate for early settlement of Murabaha? | customer | *Early settlement*, v2.0 only (8.59) |
| Who approves profit margin exceptions above 50 basis points? | customer | Abstains |
| Same question | staff | Internal pricing procedure (8.52) |
| Is the monthly maintenance fee waived for salary transfer customers? | customer | Abstains (document effective 2027) |
| Is card replacement free if I lose my card? | customer | Abstains (passage quarantined) |
| What will the weather be in Dubai tomorrow? | customer | Abstains |

Numbers are cross-encoder logits; the gate admits scores of 0 and above.

## Honest limitations

- On the paraphrase, the general-purpose reranker (trained on web search) ranks *Late payment* slightly above
  *Early settlement*. Both are admitted, so the answer contains the right fact plus an irrelevant one. Step 2
  (sentence-level groundedness) stops irrelevant sentences, and step 5 measures ranking quality so a
  domain-tuned or multilingual reranker can be justified with numbers.
- The reranker is English-only; step 6 evaluates a multilingual reranker for Arabic.

## Demo script

1. `docker compose up --build`, open http://localhost:8080.
2. Ask the paraphrase question above: it now answers with a citation instead of abstaining.
3. Open `/api/health`: `vectorIndexReady`, `rerankerActive`, fallback counters.
4. Stop the database container: answers continue from lexical retrieval; `vectorFallbacks` increases.

## Talking points

- "Keyword search finds the words; vector search finds the meaning; the reranker decides whether it answers."
- "Permissions are enforced before ranking and re-checked after, in two different layers."
- "Every component can fail, and each failure has a defined, safe, observable behaviour."
