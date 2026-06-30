package com.nechaev.config;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;


@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          AppProperties props) {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();
        RedisSerializationContext.SerializationPair<Object> valuePair =
                RedisSerializationContext.SerializationPair.fromSerializer(serializer);
        // Two caches, one manager: answers (Q→A) and sessions (chat history). Same serializer,
        // different TTLs. allEntries eviction for both is driven from CacheEvictionService.
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig(props.cache().answerTtl(), valuePair))
                .withInitialCacheConfigurations(Map.of(
                        "sessions", cacheConfig(props.rag().sessionTtl(), valuePair)))
                .build();
    }

    private static RedisCacheConfiguration cacheConfig(
            Duration ttl, RedisSerializationContext.SerializationPair<Object> valuePair) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(valuePair);
    }

    // Fail-open on Redis hiccups: log the error and call the underlying method directly
    // instead of propagating a RedisConnectionFailureException as a 500.
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
