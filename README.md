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
                                     ► PII masking ► concurrency slot                    ──►  OpenAI-compatible
fetch + ReadableStream   ◄──SSE───   meta ► delta* ► done | error   (+ keep-alive)       ◄──  chat/completions (stream)
rAF-batched rendering                hard deadline watchdog, cancel on client disconnect
```

### Gateway controls

| Concern | Control |
|---|---|
| Supply chain | No third-party runtime dependencies; JDK `HttpServer` + `HttpClient` only. Single jar runs on locked-down servers. |
| Scale | One virtual thread per request; shared HTTP/2 client with connection pooling; semaphore caps concurrent streams and sheds excess with `503 Retry-After`. |
| Abuse | Per-client token bucket (bounded LRU key space) with `429 Retry-After`; body size cap; strict UTF-8 and JSON (depth-limited) parsing. |
| Data leakage | Emirates ID, IBAN, Luhn-valid card numbers, email and UAE mobile numbers are masked **before** text leaves the bank boundary. Count is reported to the UI, values never are. |
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
event: meta   data: {"requestId":"…","model":"…","redactions":1}
event: delta  data: {"t":"In a Murabaha,"}
event: done   data: {"fragments":112,"elapsedMs":3480}
event: error  data: {"message":"The answer service is busy. Try again in a few seconds."}
```

Errors before streaming starts are JSON: `400 413 415 422 429 503`, each with `error`, `message` and `requestId`.

## Tests

```bash
cd server && mvn verify      # JSON, masking, injection guard, rate limiter, provider parsing
cd web && npm test           # SSE parser: split frames, CRLF, heartbeats, multi-line data
```

## Production notes

- The rate limiter is per node. Behind multiple replicas, enforce limits at the API gateway or back them with a shared store.
- Set `TRUST_PROXY=true` only behind a reverse proxy you control; the right-most `X-Forwarded-For` entry is used.
- Fonts load from Google Fonts with system fallbacks; self-host them for air-gapped deployments and tighten the CSP.
- Masking is pattern-based. Pair it with a DLP service for free-text names and addresses.

## License

MIT
