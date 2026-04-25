# ai-gateway

A thin Spring Boot 4 / Spring AI 2 / **Spring WebFlux (reactive)** service that wraps OpenAI behind a single REST API with:

- **Configurable OpenAI key** in `application.properties` (any OpenAI chat model — `gpt-4o`, `gpt-4o-mini`, `gpt-4.1`, `o-series`, …).
- **`POST /api/v1/chat`** — accepts `message` + `context` and **optional** `file` and `image` attachments. Fully non-blocking with `Mono`-based handlers.
- **`POST /api/v1/tts`** — converts text to Deepgram speech and returns audio bytes.
- **`POST /api/v1/voice/stream`** — streams chat text plus base64 speech chunks over Server-Sent Events.
- **In-memory semantic cache** (vector-store backed) — repeat / similar prompts return instantly without re-hitting OpenAI.
- **Per-IP token-bucket rate limiting** (Bucket4j `WebFilter`) — bounds DDoS-style flooding without an external store.
- **Reactive MongoDB** audit trail in collection `llm_request_info` (uuid / body / model / prompt / response / created_at) with a **90-day TTL** so old rows are auto-purged.
- **Mongo health** surfaced via `/actuator/health` (auto-configured by Spring Boot).
- **Hard system prompt** that refuses to leak any infra/code/runtime details.
- **Dockerfile + Railway config** for one-command deploys.

---

## 1. Configure

Set your OpenAI key (env var preferred):

```bash
export OPENAI_API_KEY=sk-...
export VOICE_KEY=dg-...
```

…or hard-code it in `src/main/resources/application.properties`:

```properties
spring.ai.openai.api-key=sk-...
spring.ai.openai.chat.options.model=gpt-4o-mini
```

### MongoDB (Atlas free tier)

The full connection URI lives **only** in an env var — never in source. We rely on Spring Boot's relaxed binding, so set:

```bash
SPRING_MONGODB_URI=mongodb+srv://<user>:<password>@<cluster>/<database>?<options>
```

(The env var name is the canonical Spring Boot upper-case form of `spring.mongodb.uri`.) Make sure the URI includes a database name in the path — that's what writes go to.

On first startup the app auto-creates the `llm_request_info` collection (Mongo creates it implicitly on first insert) and a TTL index on `created_at` (`expireAfter = P90D` ≈ 3 months) — driven by `@Indexed(expireAfter = "P90D")` on the entity plus `spring.data.mongodb.auto-index-creation=true`.

### Supabase semantic cache (optional)

By default the semantic cache is in-memory. To make it persistent across Railway restarts, create a Supabase Postgres database, enable pgvector, and set:

```bash
AI_GATEWAY_CACHE_STORE=supabase
SUPABASE_DB_JDBC_URL=jdbc:postgresql://<host>:6543/postgres?sslmode=require
SUPABASE_DB_USERNAME=<user>
SUPABASE_DB_PASSWORD=<password>
```

The app initializes the pgvector schema by default (`SUPABASE_VECTOR_INITIALIZE_SCHEMA=true`) and stores cache entries in `public.semantic_cache`. The default embedding dimension is `1536`, matching `text-embedding-3-small`.

### Other knobs in `application.properties`

| Property | Default | Meaning |
|---|---|---|
| `ai-gateway.cache.enabled` | `true` | Toggle semantic cache. |
| `ai-gateway.cache.store` | `in-memory` | Cache backend: `in-memory` or `supabase`. |
| `ai-gateway.cache.similarity-threshold` | `0.92` | Cosine-similarity above which a cached answer is reused. |
| `ai-gateway.cache.max-entries` | `500` | Local insertion-order cap for cache entries. |
| `ai-gateway.cache.ttl-seconds` | `3600` | Per-entry TTL — older hits are evicted lazily on lookup. `0` disables. |
| `ai-gateway.cache.skip-when-attachment` | `true` | Bypass cache when a file/image is attached. |
| `ai-gateway.rate-limit.capacity` | `10` | Tokens per bucket. |
| `ai-gateway.rate-limit.refill-tokens` | `10` | Tokens added per refill window. |
| `ai-gateway.rate-limit.refill-period-seconds` | `60` | Refill window. |
| `ai-gateway.tts.max-text-chars` | `5000` | Max text length for standalone TTS. |
| `ai-gateway.tts.deepgram.model` | `aura-2-draco-en` | Default Deepgram voice model. |
| `ai-gateway.voice-stream.chunk-size` | `30` | Target word count for each speech chunk. |
| `ai-gateway.voice-stream.max-audio-chunks` | `12` | Max TTS chunks per streaming response. |
| `ai-gateway.system-prompt` | (see file) | Hardened system prompt — refuses to disclose stack/infra. |

## 2. Run locally

```bash
./mvnw spring-boot:run
```

The service listens on `http://localhost:8080`.

### Plain JSON

```bash
curl -s http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Summarise the theory of relativity in 2 lines","context":"audience: high-schoolers"}'
```

### Multipart (with optional file / image)

```bash
curl -s http://localhost:8080/api/v1/chat \
  -F 'message=What does this code do?' \
  -F 'context=focus on side effects' \
  -F 'file=@/path/to/snippet.txt' \
  -F 'image=@/path/to/diagram.png'
```

Response shape:

```json
{
  "answer": "…",
  "cached": false,
  "similarity": null,
  "timestamp": "2026-04-25T09:45:00Z"
}
```

`cached: true` with a `similarity` score means the answer came from the semantic cache.

### Text to speech

```bash
curl -s http://localhost:8080/api/v1/tts \
  -H 'Content-Type: application/json' \
  -H 'Accept: audio/mpeg' \
  -d '{"text":"Hello from ai-gateway"}' \
  --output speech.mp3
```

The endpoint returns `audio/mpeg` by default. You can pass `"audioFormat":"wav"` for WAV output.

### Streaming voice

```bash
curl -N http://localhost:8080/api/v1/voice/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"Explain photosynthesis in one paragraph","context":"audience: middle school"}'
```

The stream emits `start`, `text`, `audio`, `audio-error`, `done`, and `error` events. `audio` events contain base64 audio chunks and their MIME type.

## 3. Build the Docker image

```bash
docker build -t ai-gateway:local .
docker run --rm -p 8080:8080 -e OPENAI_API_KEY=sk-... ai-gateway:local
```

## 4. Deploy to Railway

> Prereq: `brew install railway` and `railway login`.

```bash
# from the repo root
railway init                         # create a new Railway project
railway variables set OPENAI_API_KEY=sk-...
railway variables set VOICE_KEY=dg-...
railway variables set SPRING_MONGODB_URI=...        # your Atlas connection string (incl. db name)
railway variables set AI_GATEWAY_CACHE_STORE=supabase
railway variables set SUPABASE_DB_JDBC_URL=...
railway variables set SUPABASE_DB_USERNAME=...
railway variables set SUPABASE_DB_PASSWORD=...
railway up                           # builds the Dockerfile and deploys
railway domain                       # mint a public URL
```

Railway picks up `railway.toml` automatically:

- Builder: **Dockerfile**
- Health check: `GET /actuator/health`
- `$PORT` is injected and consumed by `server.port=${PORT:8080}`.

To stream logs:

```bash
railway logs
```

## 5. Architecture (short)

```
HTTP request (Reactor Netty)
   │
   ▼
RateLimitWebFilter (per-IP Bucket4j) ──► 429 if exceeded
   │
   ▼
ChatController  (/api/v1/chat — JSON or multipart, returns Mono<…>)
   │
   ▼
ChatService (reactive)
   ├─► SemanticCacheService.lookup()  ── hit ──► return cached answer
   │
   ├─► assemble user prompt (context + message + text-file body)
   ├─► attach image as Spring AI Media (FilePart → ByteArrayResource)
   ├─► ChatClient.prompt()…call().chatResponse()  (OpenAI, on boundedElastic)
   ├─► SemanticCacheService.store(query, answer)
   └─► LlmRequestInfoRepository.save(uuid, body, model, prompt, response, created_at)
```

- The semantic cache is an in-memory `SimpleVectorStore` that uses OpenAI embeddings (`text-embedding-3-small` by default) to vectorise prompts; lookups do a cosine top-1 search above the configured threshold.
- The blocking Spring AI SDK call is dispatched on `Schedulers.boundedElastic()` so the Netty event loop is never blocked.
- Mongo persistence is fully reactive (`ReactiveMongoRepository`); a TTL index on `created_at` causes Mongo to evict rows older than 90 days automatically.
