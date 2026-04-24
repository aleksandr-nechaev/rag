package com.nechaev.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public KeyGenerator questionKeyGenerator() {
        MessageDigest prototype;
        try {
            prototype = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return (target, method, params) -> {
            if (!(params[0] instanceof QuestionRequest req)) {
                throw new IllegalArgumentException("questionKeyGenerator: unexpected argument type");
            }
            try {
                MessageDigest md = (MessageDigest) prototype.clone();
                byte[] digest = md.digest(req.question().strip().toLowerCase().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          AppProperties props,
                                          ObjectMapper objectMapper) {
        RedisSerializer<AnswerResponse> serializer = new RedisSerializer<>() {
            @Override
            public byte[] serialize(AnswerResponse value) {
                if (value == null) return null;
                try {
                    return objectMapper.writeValueAsBytes(value);
                } catch (JsonProcessingException e) {
                    throw new SerializationException("Cannot serialize AnswerResponse", e);
                }
            }

            @Override
            public AnswerResponse deserialize(byte[] bytes) {
                if (bytes == null) return null;
                try {
                    return objectMapper.readValue(bytes, AnswerResponse.class);
                } catch (IOException e) {
                    throw new SerializationException("Cannot deserialize AnswerResponse", e);
                }
            }
        };

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(props.cache().answerTtl())
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
