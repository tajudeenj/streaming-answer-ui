# Streaming Answer UI

A production-grade reference for streaming LLM answers into a web interface in a regulated (banking) environment.
The demo domain is an Islamic-finance **Policy desk**: customers and front-line staff ask about Murabaha, Ijarah,
Takaful and related products and watch the answer arrive word by word.

The point is not the chat box. It is the control plane around the model call that a bank's architecture,
security and risk functions will ask for before anything goes live.

## Architecture

```
Browser (React + TS)            Gateway (Spring Boot 4.1, Spring AI 2.0, virtual threads)            Model provider
────────────────────            ─────────────────────────────────────────────────────────            ──────────────
POST /api/answer/stream ─JSON─► rate limit ► size/UTF-8/schema ► input guard ► PII masking
                                ► entitlement + effective-date filter
                                ► hybrid retrieval: BM25 + pgvector (local embeddings) ► RRF fusion
                                ► cross-encoder rerank + relevance gate ► abstain if no source
                                ► concurrency slot ──────────────────────────────────────────────►  Spring AI ChatModel
fetch + ReadableStream ◄─SSE──  meta(+sources) ► delta* ► done | error (+ keep-alive)         ◄──  (OpenAI-compatible)
rAF rendering, citations        hard deadline, cancel upstream on client disconnect

Policy library (Markdown + front matter) ─► load-time quarantine ─► BM25 index (memory)
                                                                  └► incremental embed ─► pgvector (HNSW, cosine)
```

### Gateway controls

| Concern | Control |
|---|---|
| Stack | Spring Boot 4.1 and Spring AI 2.0 (current GA lines), PostgreSQL with pgvector. Embeddings (MiniLM) and reranking (MS MARCO cross-encoder) run locally on ONNX, so policy text and questions are not sent to a third party to build or search the index. A zero-dependency JDK edition is tagged [`v1-zero-dep`](../../tree/v1-zero-dep). |
| Scale | Virtual threads per request; bounded Hikari pool; semaphore caps concurrent streams and sheds excess with `503 Retry-After`; reranker inference capped at one slot per core with a 2 s wait budget before degrading. |
| Degradation | Vector store unavailable: lexical retrieval continues. Reranker unavailable: lexical relevance gate. No qualifying source: abstain. Each fallback is counted in `/api/health`. |
| Abuse | Per-client token bucket (bounded LRU key space) with `429 Retry-After`; body size cap; strict UTF-8 and JSON (depth-limited) parsing. |
| Data leakage | Emirates ID, IBAN, Luhn-valid card numbers, email and UAE mobile numbers are masked **before** text leaves the bank boundary. Count is reported to the UI, values never are. |
| Grounding | Answers come only from the policy library and cite every statement. Retrieval filters by audience and effective date **before** ranking (in SQL for vectors, re-checked in Java), fuses lexical and semantic rankings, and admits only passages the cross-encoder scores as relevant. With no qualifying source it abstains without a model call. See the [Step 1](docs/lessons/step-1-grounding.md) and [Step 1b](docs/lessons/step-1b-hybrid-retrieval.md) lessons. |
| Poisoned documents | Policy chunks with hidden markup, invisible characters or text addressed to the model are quarantined at load time and counted in `/api/health`. Source text is fenced and labelled as data in the prompt. |
| Prompt injection | Control, zero-width and bidi characters stripped; NFKC normalisation; instruction-override phrasing rejected with `422`; hardened system prompt. Treated as a pre-filter, not a guarantee. |
| Resilience | Hard total deadline (header timeout alone does not bound a stream); upstream closed as soon as the browser disconnects; SSE keep-alive for proxies; graceful drain on shutdown. |
| Output safety | All JSON output escapes `< > &` and line separators; UI renders text only (no HTML injection path). |
| Web security | CSP, `X-Frame-Options`, `nosniff`, `no-referrer`, COOP; CORS off unless an exact origin is configured; static files protected against traversal and symlink escape. |
| Observability | Request id on every response (accepted from upstream if well-formed); structured logs with outcome and latency, never prompt content; counters at `/api/health`. |

### Load test (v1 zero-dependency edition, mock provider, 256 MB heap)

400 simultaneous streams against `MAX_CONCURRENT_STREAMS=200`: 200 completed, 200 rejected cleanly with `503`, 0 errors.

## Run locally

Requirements: Docker Desktop. For development outside Docker: JDK 21, Maven 3.9 and Node 22.

```bash
# Everything in containers (database + gateway with the built web app)
docker compose up --build
# open http://localhost:8080
```

The first start downloads the embedding and reranker models (about 110 MB) into a named volume; later
starts reuse them. Readiness: `http://localhost:8080/actuator/health/readiness`. Metrics: `/api/health`.

Development loop (hot reload for the web app):

```bash
docker compose up -d db                 # PostgreSQL + pgvector only
cd web && npm ci && npm run build       # or: npm run dev  (proxies /api to :8080)
cd ../server && mvn spring-boot:run     # uses DB at localhost:5432
```

### Real model

```bash
export LLM_PROVIDER=openai
export LLM_API_KEY=...            # never commit this
export LLM_MODEL=gpt-4o-mini
# Azure OpenAI:
# OpenAI-compatible endpoints (Azure OpenAI, vLLM, LiteLLM): set LLM_BASE_URL
docker compose up --build
```

All settings are listed in [`.env.example`](.env.example) and validated at startup.

### Policy library

Documents live in [`policies/`](policies/) as Markdown with front matter (`id`, `title`, `version`,
`effective_from`, optional `effective_to`, `audience`). The sample library is illustrative and describes a
fictional bank. Point `POLICY_DIR` at your own library; files are validated and screened at startup.

Audience defaults to `customer`. To serve staff content, place the gateway behind an identity-aware proxy that
sets `X-User-Audience` (and strips any client-supplied value), then set `TRUST_IDENTITY_HEADER=true`.

## API

`POST /api/answer/stream` with `Content-Type: application/json`

```json
{ "question": "How is Murabaha pricing different from interest?",
  "history": [ { "role": "user", "content": "..." }, { "role": "assistant", "content": "..." } ] }
```

Response is `text/event-stream`:

```
event: meta   data: {"requestId":"…","model":"…","redactions":1,"audience":"customer","grounded":true,
                     "sources":[{"ref":"S1","docId":"murabaha-terms","title":"Murabaha Finance Terms","version":"2.0",
                                 "effectiveFrom":"2026-01-01","section":"Early settlement","hash":"3f2a9c…"}]}
event: delta  data: {"t":"Customers may settle early at any time."}
event: delta  data: {"t":" [S1]"}
event: done   data: {"fragments":42,"abstained":false,"elapsedMs":1480}
event: error  data: {"message":"The answer service is busy. Try again in a few seconds."}
```

Errors before streaming starts are JSON: `400 413 415 422 429 503`, each with `error`, `message` and `requestId`.

## Tests

```bash
cd server && mvn verify      # unit tests, plus an integration test against real pgvector (Testcontainers),
                             # local embeddings and the cross-encoder; skipped automatically without Docker
cd web && npm test           # SSE parser, citation rendering
```

## Production notes

- The rate limiter is per node. Behind multiple replicas, enforce limits at the API gateway or back them with a shared store.
- Set `TRUST_PROXY=true` only behind a reverse proxy you control; the right-most `X-Forwarded-For` entry is used.
- Fonts load from Google Fonts with system fallbacks; self-host them for air-gapped deployments and tighten the CSP.
- Masking is pattern-based. Pair it with a DLP service for free-text names and addresses.
- The policy library is loaded at startup and synced to pgvector incrementally (only new passages are embedded,
  retired ones are deleted). Restart or roll the deployment to publish; effective dates let you publish ahead
  of a change and it switches on automatically.
- Pin `RERANKER_MODEL_SHA256` (and mirror the model files internally) in production so a tampered upstream
  artifact is refused. Point `RERANKER_MODEL_URI` / `RERANKER_TOKENIZER_URI` at the internal mirror.

## Build log

| Step | Topic | Lesson |
|---|---|---|
| 1 | Grounding with entitlements, versioning and poisoned-document defense | [docs/lessons/step-1-grounding.md](docs/lessons/step-1-grounding.md) |
| 1b | Spring AI 2.0, hybrid retrieval (BM25 + pgvector, RRF) and cross-encoder reranking | [docs/lessons/step-1b-hybrid-retrieval.md](docs/lessons/step-1b-hybrid-retrieval.md) |

## License

MIT
