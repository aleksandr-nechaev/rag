package com.nechaev.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProtectionConfig {

    @Bean
    public RateLimiter aiRateLimiter(AppProperties props) {
        AppProperties.Ai ai = props.protection().ai();
        return RateLimiter.of("ai", RateLimiterConfig.custom()
                .limitForPeriod(ai.limitForPeriod())
                .limitRefreshPeriod(ai.limitRefreshPeriod())
                .timeoutDuration(ai.timeoutDuration())
                .build());
    }

    @Bean
    public Bulkhead databaseBulkhead(AppProperties props) {
        AppProperties.Database db = props.protection().database();
        return Bulkhead.of("database", BulkheadConfig.custom()
                .maxConcurrentCalls(db.maxConcurrentCalls())
                .maxWaitDuration(db.maxWaitDuration())
                .build());
    }
}
