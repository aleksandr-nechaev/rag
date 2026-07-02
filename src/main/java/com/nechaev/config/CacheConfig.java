package com.nechaev.config;

import com.nechaev.dto.QuestionRequest;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;


@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          AppProperties props) {
        RedisSerializationContext.SerializationPair<Object> valuePair =
                RedisSerializationContext.SerializationPair.fromSerializer(cacheValueSerializer());
        // Two caches, one manager: answers (Q→A) and sessions (chat history). Same serializer,
        // different TTLs. allEntries eviction for both is driven from CacheEvictionService.
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig(props.cache().answerTtl(), valuePair))
                .withInitialCacheConfigurations(Map.of(
                        "sessions", cacheConfig(props.rag().sessionTtl(), valuePair)))
                .build();
    }

    // Default typing is required (cache values are declared as Object), but constrained to the
    // types we actually cache: AnswerResponse / SessionMessage (com.nechaev.*) and the ArrayList
    // wrapper of the sessions history. Anything else in a @class field is rejected on read —
    // an attacker with write access to Redis must not be able to trigger gadget-chain
    // deserialization. Exposed so SessionSerializationRoundTripTest exercises the exact
    // production serializer.
    public static GenericJacksonJsonRedisSerializer cacheValueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.nechaev.")
                        .allowIfSubType(ArrayList.class)
                        .build())
                .build();
    }

    // Key for the "answers" cache: SHA-256 of the normalized question. Keeps raw user text
    // (potentially with someone's PII) out of Redis key names and bounds key length regardless
    // of the 1000-char question limit.
    @Bean
    public KeyGenerator sha256KeyGenerator() {
        return (target, method, params) -> {
            if (params.length == 1 && params[0] instanceof QuestionRequest request) {
                return sha256Hex(request.question().strip().toLowerCase());
            }
            throw new IllegalArgumentException(
                    "sha256KeyGenerator expects a single QuestionRequest argument, got "
                            + params.length + " params on " + method);
        };
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
