package com.nechaev.service;

import com.nechaev.config.AppProperties;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AnswerCacheService {

    private static final String KEY_PREFIX = "answer_cache:";

    // MessageDigest is not thread-safe — one instance per virtual thread via ThreadLocal.
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public AnswerCacheService(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.ttl = props.cache().answerTtl();
    }

    public void store(Question question, Answer answer) {
        redis.opsForValue().set(key(question), answer.text(), ttl);
    }

    public Optional<Answer> find(Question question) {
        return Optional.ofNullable(redis.opsForValue().get(key(question))).map(Answer::new);
    }

    private static String key(Question question) {
        return KEY_PREFIX + hash(question.text());
    }

    // Normalize before hashing so "What is Java?" and "what is java?" hit the same entry.
    private static String hash(String text) {
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] digest = md.digest(text.strip().toLowerCase().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
