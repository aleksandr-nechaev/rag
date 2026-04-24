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
    public Bulkhead ragPipelineBulkhead(AppProperties props) {
        AppProperties.RagPipeline rp = props.protection().ragPipeline();
        return Bulkhead.of("rag-pipeline", BulkheadConfig.custom()
                .maxConcurrentCalls(rp.maxConcurrentCalls())
                .maxWaitDuration(rp.maxWaitDuration())
                .build());
    }
}
