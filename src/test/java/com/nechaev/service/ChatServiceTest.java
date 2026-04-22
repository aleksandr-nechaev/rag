package com.nechaev.service;

import com.nechaev.config.AppProperties;
import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.mapper.ChatMapper;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient.Builder chatClientBuilder;

    @Mock VectorStore vectorStore;
    @Mock Bulkhead databaseBulkhead;
    @Mock RateLimiter aiRateLimiter;
    @Mock ChatMapper chatMapper;
    @Mock AppProperties appProperties;

    ChatService chatService;

    static final QuestionRequest REQUEST  = new QuestionRequest("What is your experience?");
    static final Question        QUESTION = new Question("What is your experience?");

    @BeforeEach
    void setUp() {
        AppProperties.Rag rag = new AppProperties.Rag(3);
        when(appProperties.rag()).thenReturn(rag);
        chatService = new ChatService(chatClientBuilder, vectorStore, databaseBulkhead,
                aiRateLimiter, chatMapper, appProperties);
        when(chatMapper.toQuestion(REQUEST)).thenReturn(QUESTION);
    }

    @Test
    void answerBulkheadFullThrowsBulkheadFullException() {
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(false);
        when(databaseBulkhead.getBulkheadConfig()).thenReturn(BulkheadConfig.ofDefaults());

        assertThatThrownBy(() -> chatService.answer(REQUEST))
                .isInstanceOf(BulkheadFullException.class);
    }

    @Test
    void answerRateLimitReachedReturnsRawFallback() {
        Document doc = new Document("SKILLS\nJava, Spring Boot");
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(doc));
        when(aiRateLimiter.acquirePermission()).thenReturn(false);
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("AI is currently unavailable");
        assertThat(result.answer()).contains("SKILLS");
        verify(databaseBulkhead).onComplete();
    }

    @Test
    void answerAiCallSucceedsReturnsAiAnswer() {
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().content())
                .thenReturn("Aleksandr has 5 years of Java experience.");
        AnswerResponse expected = new AnswerResponse("Aleksandr has 5 years of Java experience.");
        when(chatMapper.toResponse(any())).thenReturn(expected);

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void answerAiCallFailsReturnsRawFallback() {
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().content())
                .thenThrow(new RuntimeException("AI unavailable"));
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("AI is currently unavailable");
        verify(databaseBulkhead).onComplete();
    }

    @Test
    void answerNoRelevantDocsFallbackMentionsNoInfoFound() {
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(aiRateLimiter.acquirePermission()).thenReturn(false);
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("no relevant information");
        verify(databaseBulkhead).onComplete();
    }

    @Test
    void answerBulkheadPermissionAlwaysReleasedEvenOnException() {
        when(databaseBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> chatService.answer(REQUEST))
                .isInstanceOf(RuntimeException.class);
        verify(databaseBulkhead).onComplete();
    }
}
