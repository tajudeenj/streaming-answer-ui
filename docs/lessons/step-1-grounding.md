# Step 1: Grounding with entitlements, versioning and poisoned-document defense

## The problem

Before this step the model answered from its own training data. That has three consequences a bank
cannot accept:

1. **It can be wrong with confidence.** The model has never read this bank's terms, so it fills gaps with
   plausible industry generalities (OWASP LLM07 Misinformation).
2. **Nothing is traceable.** A complaint or an audit asks "where did the assistant get that?" and there is no answer.
3. **Policy changes are invisible.** When terms change on 1 January, the model keeps repeating the old position.

Naive retrieval-augmented generation (RAG) fixes the first two and creates three new risks: customers
retrieving staff-only documents, expired or not-yet-effective documents being cited, and attacker text
hidden inside a document steering the model (indirect prompt injection, OWASP LLM01).

## The design

```
question ─► mask PII ─► filter library ─► rank ─► relevance gate ─┬─► no source: abstain (no model call)
                        (audience +      (BM25)  (score + query   │
                         effective date)          coverage)       └─► sources fenced as data ─► model ─► cited answer
                 ▲
   load time: parse front matter, split by section, quarantine poisoned chunks, hash each document
```

| Decision | Why |
|---|---|
| Filter **before** ranking | Content the caller may not see never enters scoring, so it cannot leak through ranking side effects or the prompt. |
| Audience from a trusted gateway only | The browser cannot raise its own entitlement; the `X-User-Audience` header is honoured only when `TRUST_IDENTITY_HEADER=true` and an identity-aware proxy sets it. |
| Effective-date window per document version | Expired (`murabaha-terms` 1.0) and future (`fees-schedule` 2027) documents are excluded automatically on the right day, in the bank's time zone. |
| Quarantine at ingestion | Hidden markup, invisible characters and text addressed to the model are dropped once at load time, logged, and counted in `/api/health`. Checking at query time would cost every request. |
| BM25 lexical retrieval | Deterministic, explainable, no embedding model or vector database to approve. Policy text is full of exact terms (Murabaha, Ijarah, rebate) where lexical matching is strong. A vector index can be added behind the same retriever and fused later. |
| Relevance gate (minimum score and query coverage) | A passage that shares one or two words with the question is not evidence. Below the gate the system abstains. |
| Abstain without calling the model | A refusal costs nothing, cannot hallucinate, and is the correct answer when the library is silent. |
| Sources fenced and labelled as data | Angle brackets and quotes in source text are neutralised so a document cannot close its own `<source>` block; the system prompt states that sources are not instructions. |
| Document hash in every citation | Ties each answer to the exact bytes of the document version that was used; step 4 writes it to the audit trail. |

## Demo script

Run the gateway with the sample library (`policies/`) and ask:

| Question | Expected behaviour | Control shown |
|---|---|---|
| Can I get a rebate if I settle my Murabaha early? | Cites Murabaha terms **2.0**. Version 1.0 said rebates were not offered. | Versioning |
| Does rent stop if my Ijarah home is destroyed? | Cites the Ijarah *Total loss* section. | Grounding and citation |
| Who approves profit margin exceptions above 50 basis points? | Abstains as a customer. With `TRUST_IDENTITY_HEADER=true` and header `X-User-Audience: staff`, cites the internal procedure. | Entitlements |
| Is the monthly account maintenance fee waived for salary transfer customers? | Abstains: the 2027 fees schedule is not yet effective. | Effective dating |
| Is card replacement free if I lose my card? | Abstains: that section carried a hidden instruction and was quarantined. Check the startup log. | Indirect injection defense |
| What is the weather in Dubai tomorrow? | Abstains with no model call (`model: none` in the stream metadata). | Relevance gate |

## What this step does not solve yet

- The model can still **misstate** a cited source. Step 2 checks each sentence against its source before release.
- Lexical retrieval misses paraphrases with no shared words. The eval in step 5 measures retrieval recall so the
  gap is a number, not a guess.
- Arabic text is indexed but not yet normalised (step 6).

## Talking points

- "Grounding is an access-control problem before it is a relevance problem."
- "The cheapest hallucination is the one you never ask the model to produce: abstain when the library is silent."
- "Every document is a potential attacker. We screen content at ingestion, not just user input."
