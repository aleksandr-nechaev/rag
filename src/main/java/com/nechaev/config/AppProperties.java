package com.nechaev.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cache cache, Protection protection, List<String> allowedOrigins, Ingestion ingestion, Rag rag, Prompts prompts, Models models) {

    public record Cache(Duration answerTtl) {}

    public record Protection(Ai ai, RagPipeline ragPipeline, PerIpRateLimit perIpRateLimit) {}

    public record Ai(int limitForPeriod, Duration limitRefreshPeriod, Duration timeoutDuration) {}

    // Bulkhead around the retrieval phase only (query embedding + pgvector search) — the
    // part of the pipeline that holds a JDBC connection. Hence max-concurrent-calls must
    // equal spring.datasource.hikari.maximum-pool-size. The AI call that follows holds no
    // connection and is bounded by Ai.limitForPeriod instead — see ChatService.
    public record RagPipeline(int maxConcurrentCalls, Duration maxWaitDuration) {}

    // Distributed per-client-IP rate limit, backed by Redis (atomic INCR+EXPIRE Lua script).
    // trustedProxy=true reads X-Forwarded-For first hop (use behind ALB/CloudFront);
    // false uses request.getRemoteAddr() (safer for direct exposure).
    // failOpen=true allows requests on Redis outage (preserves availability under DDoS-on-Redis);
    // false denies all (strict abuse prevention).
    public record PerIpRateLimit(int limitForPeriod, Duration limitRefreshPeriod,
                                 boolean trustedProxy, boolean failOpen) {}

    public record Ingestion(String resumePath, DataSize maxSize) {}

    public record Rag(int topK, int maxHistory, Duration sessionTtl) {}

    public record Prompts(String systemVersion) {}

    // Primary model is tried first; on any RuntimeException, the fallback is attempted.
    // If both fail, the raw resume chunks are returned.
    public record Models(String primary, String fallback) {}
}
