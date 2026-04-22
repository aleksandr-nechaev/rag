# Resume RAG Chatbot

A RAG (Retrieval-Augmented Generation) application that answers questions about Aleksandr Nechaev using his resume as a knowledge base. Powered by Google Gemini and pgvector.

## How It Works

1. On startup, the resume PDF is split into sections, embedded locally (all-MiniLM-L6-v2 via ONNX), and stored in pgvector. Re-ingestion only happens when the PDF changes (SHA-256 hash check), protected by a PostgreSQL advisory lock for multi-instance safety.
2. A question arrives via REST or WebSocket → top relevant resume sections are retrieved via cosine similarity search (configurable, default 3) → sent to Gemini with a system prompt → answer is cached in Redis for 1 hour.
3. If Gemini is unavailable or rate-limited, raw resume sections are returned as a fallback and also cached.

## Stack

- Java 25 + Spring Boot 4.0.5, virtual threads enabled
- Spring AI 2.0.0-M4: Google GenAI (Gemini), pgvector, local ONNX embeddings
- PostgreSQL with pgvector extension
- Redis (answer cache, TTL 1h)
- Resilience4j: rate limiter (15 req/m to AI) + bulkhead (5 concurrent DB calls)
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

Connect to `http://localhost:8080/ws`, subscribe to `/topic/answers`, publish to `/app/ask`:

```javascript
const client = new StompJs.Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws')
});
client.onConnect = () => {
    client.subscribe('/topic/answers', msg => console.log(JSON.parse(msg.body)));
    client.publish({ destination: '/app/ask', body: JSON.stringify({ question: 'Tell me about Aleksandr' }) });
};
client.activate();
```

## Build & Test

```bash
./gradlew build                                      # compile + test
./gradlew test --tests "com.nechaev.SomeTest"        # single test class
./gradlew clean build                                # clean rebuild
```
