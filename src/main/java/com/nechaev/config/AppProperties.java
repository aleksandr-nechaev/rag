package com.nechaev.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cache cache, Protection protection, List<String> allowedOrigins, Ingestion ingestion, Rag rag) {

    public record Cache(Duration answerTtl) {}

    public record Protection(Ai ai, Database database) {}

    public record Ai(int limitForPeriod, Duration limitRefreshPeriod, Duration timeoutDuration) {}

    // max-concurrent-calls must equal spring.datasource.hikari.maximum-pool-size
    public record Database(int maxConcurrentCalls, Duration maxWaitDuration) {}

    public record Ingestion(String resumePath) {}

    public record Rag(int topK) {}
}
