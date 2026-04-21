package com.nechaev.service;

import com.nechaev.config.AppProperties;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerCacheServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    AnswerCacheService cacheService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                new AppProperties.Cache(Duration.ofHours(1)),
                new AppProperties.Protection(
                        new AppProperties.Ai(1, Duration.ofSeconds(3), Duration.ZERO),
                        new AppProperties.Database(10, Duration.ZERO)),
                List.of("http://localhost:8080"));
        when(redis.opsForValue()).thenReturn(valueOps);
        cacheService = new AnswerCacheService(redis, props);
    }

    @Test
    void findKeyExistsReturnsAnswer() {
        Question question = new Question("What is Java?");
        when(valueOps.get(any())).thenReturn("Java is a programming language.");

        Optional<Answer> result = cacheService.find(question);

        assertThat(result).isPresent();
        assertThat(result.get().text()).isEqualTo("Java is a programming language.");
    }

    @Test
    void findKeyMissingReturnsEmpty() {
        Question question = new Question("Unknown question");
        when(valueOps.get(any())).thenReturn(null);

        Optional<Answer> result = cacheService.find(question);

        assertThat(result).isEmpty();
    }

    @Test
    void storeWritesToRedisWithCorrectTtl() {
        Question question = new Question("What is Java?");
        Answer answer = new Answer("Java answer");

        cacheService.store(question, answer);

        verify(valueOps).set(any(), eq("Java answer"), eq(Duration.ofHours(1)));
    }

    @Test
    void findAndStoreUseSameCacheKey() {
        Question question = new Question("What is Java?");
        Answer answer = new Answer("Java answer");

        cacheService.store(question, answer);

        ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(storeKey.capture(), any(), any(Duration.class));

        when(valueOps.get(any())).thenReturn("Java answer");
        cacheService.find(question);

        ArgumentCaptor<String> findKey = ArgumentCaptor.forClass(String.class);
        verify(valueOps).get(findKey.capture());

        assertThat(storeKey.getValue()).isEqualTo(findKey.getValue());
    }

    @Test
    void findNormalizesQuestionBeforeHashing() {
        Question lower = new Question("what is java?");
        Question upper = new Question("WHAT IS JAVA?");
        Question padded = new Question("  What is Java?  ");

        when(valueOps.get(any())).thenReturn("answer");
        cacheService.find(lower);
        cacheService.find(upper);
        cacheService.find(padded);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(3)).get(keys.capture());

        assertThat(keys.getAllValues()).containsOnly(keys.getAllValues().get(0));
    }
}
