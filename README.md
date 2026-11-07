# Streaming Answer UI

A production-grade reference for streaming LLM answers into a web interface in a regulated (banking) environment.
The demo domain is an Islamic-finance **Policy desk**: customers and front-line staff ask about Murabaha, Ijarah,
Takaful and related products and watch the answer arrive word by word.

The point is not the chat box. It is the control plane around the model call that a bank's architecture,
security and risk functions will ask for before anything goes live.

## Architecture

```
Browser (React + TS)                     Gateway (JDK 21, zero runtime deps)                 Model provider
──────────────────────                   ───────────────────────────────────────              ───────────────
POST /api/answer/stream  ──JSON──►  rate limit ► size/UTF-8/schema checks ► input guard
                                     ► PII masking ► policy retrieval (entitled, effective,
                                       relevant) ► abstain if no source ► concurrency slot ──►  OpenAI-compatible
fetch + ReadableStream   ◄──SSE───   meta(+sources) ► delta* ► done | error (+ keep-alive) ◄──  chat/completions (stream)
rAF-batched rendering, citations     hard deadline watchdog, cancel on client disconnect

Policy library (Markdown + front matter) ─► load-time quarantine of poisoned chunks ─► BM25 index
```

### Gateway controls

| Concern | Control |
|---|---|
| Supply chain | No third-party runtime dependencies; JDK `HttpServer` + `HttpClient` only. Single jar runs on locked-down servers. |
| Scale | One virtual thread per request; shared HTTP/2 client with connection pooling; semaphore caps concurrent streams and sheds excess with `503 Retry-After`. |
| Abuse | Per-client token bucket (bounded LRU key space) with `429 Retry-After`; body size cap; strict UTF-8 and JSON (depth-limited) parsing. |
| Data leakage | Emirates ID, IBAN, Luhn-valid card numbers, email and UAE mobile numbers are masked **before** text leaves the bank boundary. Count is reported to the UI, values never are. |
| Grounding | Answers come only from the policy library and cite every statement. Retrieval filters by audience and effective date **before** ranking, applies a score and query-coverage gate, and abstains without a model call when no source qualifies. See [Step 1 lesson](docs/lessons/step-1-grounding.md). |
| Poisoned documents | Policy chunks with hidden markup, invisible characters or text addressed to the model are quarantined at load time and counted in `/api/health`. Source text is fenced and labelled as data in the prompt. |
| Prompt injection | Control, zero-width and bidi characters stripped; NFKC normalisation; instruction-override phrasing rejected with `422`; hardened system prompt. Treated as a pre-filter, not a guarantee. |
| Resilience | Hard total deadline (header timeout alone does not bound a stream); upstream closed as soon as the browser disconnects; SSE keep-alive for proxies; graceful drain on shutdown. |
| Output safety | All JSON output escapes `< > &` and line separators; UI renders text only (no HTML injection path). |
| Web security | CSP, `X-Frame-Options`, `nosniff`, `no-referrer`, COOP; CORS off unless an exact origin is configured; static files protected against traversal and symlink escape. |
| Observability | Request id on every response (accepted from upstream if well-formed); structured logs with outcome and latency, never prompt content; counters at `/api/health`. |

### Load test (mock provider, 256 MB heap)

400 simultaneous streams against `MAX_CONCURRENT_STREAMS=200`: 200 completed, 200 rejected cleanly with `503`, 0 errors.

## Run locally

Requirements: JDK 21, Maven 3.9, Node 22.

```bash
# 1. web app
cd web
npm ci
npm run build

# 2. gateway (mock provider, no key needed)
cd ../server
mvn -q package
java -jar target/streaming-answer-ui.jar
# open http://localhost:8080
```

For frontend development with hot reload, run the gateway as above and `npm run dev` in `web/` (proxies `/api` to `:8080`).

### Real model

```bash
export LLM_PROVIDER=openai
export LLM_API_KEY=...            # never commit this
export LLM_MODEL=gpt-4o-mini
# Azure OpenAI:
# export LLM_ENDPOINT=https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=2024-10-21
# export LLM_AUTH_HEADER=api-key
java -jar target/streaming-answer-ui.jar
```

All settings are listed in [`.env.example`](.env.example) and validated at startup.

### Policy library

Documents live in [`policies/`](policies/) as Markdown with front matter (`id`, `title`, `version`,
`effective_from`, optional `effective_to`, `audience`). The sample library is illustrative and describes a
fictional bank. Point `POLICY_DIR` at your own library; files are validated and screened at startup.

Audience defaults to `customer`. To serve staff content, place the gateway behind an identity-aware proxy that
sets `X-User-Audience` (and strips any client-supplied value), then set `TRUST_IDENTITY_HEADER=true`.

### Docker

```bash
docker build -t streaming-answer-ui .
docker run --rm -p 8080:8080 -e LLM_PROVIDER=mock streaming-answer-ui
```

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
cd server && mvn verify      # JSON, masking, injection guards, rate limiter, provider parsing,
                             # policy loading and quarantine, entitlement / versioning / relevance retrieval
cd web && npm test           # SSE parser, citation rendering
```

## Production notes

- The rate limiter is per node. Behind multiple replicas, enforce limits at the API gateway or back them with a shared store.
- Set `TRUST_PROXY=true` only behind a reverse proxy you control; the right-most `X-Forwarded-For` entry is used.
- Fonts load from Google Fonts with system fallbacks; self-host them for air-gapped deployments and tighten the CSP.
- Masking is pattern-based. Pair it with a DLP service for free-text names and addresses.
- The policy index is built at startup. Restart (or roll the deployment) to publish new document versions;
  effective dates mean you can publish ahead of a change and it switches on automatically.

## Build log

| Step | Topic | Lesson |
|---|---|---|
| 1 | Grounding with entitlements, versioning and poisoned-document defense | [docs/lessons/step-1-grounding.md](docs/lessons/step-1-grounding.md) |

## License

MIT
