# Resume RAG Chatbot

A RAG (Retrieval-Augmented Generation) application that answers questions about Aleksandr Nechaev using his resume as a knowledge base. Powered by Google Gemini and pgvector.

## How It Works

1. On startup, the resume PDF is split into sections, embedded locally (all-MiniLM-L6-v2 via ONNX), and stored in pgvector. Re-ingestion only happens when the PDF changes (SHA-256 hash check), protected by a PostgreSQL advisory lock for multi-instance safety.
2. A question arrives via REST or WebSocket → top relevant resume sections are retrieved via cosine similarity search (configurable, default 3) → sent to Gemini with a system prompt → answer is cached in Redis for 1 hour.
3. The AI call uses a **two-tier model fallback chain** (see below). If both models fail, raw resume sections are returned as a fallback and cached.

### Model fallback chain

Configured under `app.models`:

| Tier | Model | Free-tier RPM | Triggered when |
|---|---|---|---|
| Primary | `gemini-2.5-flash` | 10 | First attempt for every request |
| Fallback | `gemini-2.5-flash-lite` | 15 | Primary throws any `RuntimeException` (rate limit, 5xx, timeout) |
| Raw chunks | — | — | Both models throw, OR local rate limiter (`app.protection.ai.limit-for-period`) is exhausted |

Behaviour notes:
- Each user request consumes **exactly one** local rate-limiter permit, regardless of how many models are tried — fallback is "free" from our limiter's perspective.
- Local limiter is sized at **25 RPM** to span the combined Google quota (10 flash + 15 flash-lite). At sustained 25 RPM, expect ~10 served by primary and ~15 by fallback.
- Outcomes are tagged in `ai.requests` Counter: `ai`, `ai_fallback_model`, `fallback_rate_limit`, `fallback_error`. Watch `ai_fallback_model` to gauge how often primary is degrading.
- Models live in `app.models.{primary,fallback}`. Spring AI's default model (`spring.ai.google.genai.chat.options.model`) references `${app.models.primary}` so there's a single source of truth.

## Stack

- Java 25 + Spring Boot 4.0.5, virtual threads enabled
- Spring AI 2.0.0-M4: Google GenAI (Gemini), pgvector, local ONNX embeddings
- PostgreSQL with pgvector extension
- Redis (answer cache, TTL 1h)
- Resilience4j: rate limiter (25 req/m to AI, sized for the fallback chain) + bulkhead (5 concurrent DB calls)
- MapStruct for DTO ↔ domain model mapping
- Liquibase for schema management

## Requirements

- Java 25
- Docker (to launch PostgreSQL + Redis)

Start infrastructure:

```bash
docker compose up -d
```

## Environment Variables

| Variable           | Default     | Description                        |
|--------------------|-------------|------------------------------------|
| `GOOGLE_AI_API_KEY`| —           | Google AI Studio API key (required)|
| `DB_HOST`          | `localhost` | PostgreSQL host                    |
| `DB_PORT`          | `5432`      | PostgreSQL port                    |
| `DB_NAME`          | `postgres`  | Database name                      |
| `DB_USERNAME`      | `postgres`  | Database user                      |
| `DB_PASSWORD`      | `postgres`  | Database password                  |
| `REDIS_HOST`       | `localhost` | Redis host                         |
| `REDIS_PORT`       | `6379`      | Redis port                         |
| `REDIS_USER`       | `default`   | Redis username                     |
| `REDIS_PASSWORD`   | `redis`     | Redis password                     |

Get a free Google AI Studio API key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).

## Run

```bash
export GOOGLE_AI_API_KEY=your_key_here
./gradlew bootRun
```

First run downloads the ONNX sentence transformer model (~90 MB) from Hugging Face.

## API

### Web UI

Open [http://localhost:8080](http://localhost:8080) — built-in chat interface (`src/main/resources/static/index.html`), no setup required. Type a question and press **Enter** or **Send**. Connects to the backend via WebSocket (STOMP/SockJS) automatically.

### REST

```bash
curl -X POST http://localhost:8080/api/v1/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is Aleksandr'\''s experience with Java?"}'
```

### WebSocket (STOMP over SockJS)

Connect to `http://localhost:8080/ws`, subscribe to `/user/queue/v1/answers` (per-session reply), publish to `/app/v1/ask`:

```javascript
const client = new StompJs.Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws')
});
client.onConnect = () => {
    client.subscribe('/user/queue/v1/answers', msg => console.log(JSON.parse(msg.body)));
    client.publish({ destination: '/app/v1/ask', body: JSON.stringify({ question: 'Tell me about Aleksandr' }) });
};
client.activate();
```

### API Documentation

Once the app is running:

| Endpoint                                                   | Description                                          |
|------------------------------------------------------------|------------------------------------------------------|
| [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) | REST API explorer (OpenAPI 3 via springdoc)         |
| [`/v3/api-docs`](http://localhost:8080/v3/api-docs)         | OpenAPI 3 JSON spec                                 |
| [`/springwolf/asyncapi-ui.html`](http://localhost:8080/springwolf/asyncapi-ui.html) | WebSocket/STOMP API explorer (AsyncAPI via springwolf) |
| [`/springwolf/docs`](http://localhost:8080/springwolf/docs) | AsyncAPI JSON spec                                  |
| [`/actuator/metrics/ai.tokens`](http://localhost:8080/actuator/metrics/ai.tokens) | Gemini token usage counters (Micrometer)            |
| [`/actuator/prometheus`](http://localhost:8080/actuator/prometheus) | All metrics in Prometheus exposition format         |

These admin endpoints require **HTTP basic auth** (role `ADMIN`). Default username is `admin`; password is auto-generated on each startup and printed to the console (`Using generated security password: ...`). Override via env var `SPRING_SECURITY_USER_PASSWORD` (and `ADMIN_USER` for the username) before deploying. Public endpoints (`/`, `/ws/**`, `/api/v1/**`) remain unauthenticated.

For **production deploys**, run with `SPRING_PROFILES_ACTIVE=prod`. The `prod` profile activates a startup check that fails fast if `SPRING_SECURITY_USER_PASSWORD` is not set, preventing the auto-generated password from leaking through stdout/log collection.

## Build & Test

```bash
./gradlew build                                      # compile + test
./gradlew test --tests "com.nechaev.SomeTest"        # single test class
./gradlew clean build                                # clean rebuild
```
