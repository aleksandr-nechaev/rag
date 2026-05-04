package com.nechaev.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

// Spring Boot 4 dropped session auto-configuration, so we wire the Redis-backed
// HttpSession explicitly. 'Indexed' enables SessionExpired/Deleted events through
// Redis keyspace notifications (used by SessionLifecycleListener to drop chat history).
@Configuration
@EnableRedisIndexedHttpSession(
        maxInactiveIntervalInSeconds = 3600,
        redisNamespace = "rag:session"
)
public class SpringSessionConfig {
}
