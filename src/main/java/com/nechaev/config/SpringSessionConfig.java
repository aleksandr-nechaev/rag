package com.nechaev.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// Spring Boot 4 dropped session auto-configuration, so we wire the Redis-backed
// HttpSession explicitly. We use the non-indexed variant: it does not require Redis
// keyspace notifications, so it avoids the CONFIG command that AWS ElastiCache blocks.
// Chat-history cleanup relies on the per-key TTL set in ChatService.saveHistory.
@Configuration
@EnableRedisHttpSession(
        maxInactiveIntervalInSeconds = 3600,
        redisNamespace = "rag:session"
)
public class SpringSessionConfig {
}
