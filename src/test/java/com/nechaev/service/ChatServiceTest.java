package com.nechaev.service;

import tools.jackson.databind.ObjectMapper;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient.Builder chatClientBuilder;

    @Mock VectorStore vectorStore;
    @Mock Bulkhead ragPipelineBulkhead;
    @Mock RateLimiter aiRateLimiter;
    @Mock ChatMapper chatMapper;
    @Mock AppProperties appProperties;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    final ObjectMapper objectMapper = new ObjectMapper();

    ChatService chatService;
    AiUsageMetrics aiUsageMetrics;
    @Mock PromptCatalog promptCatalog;
    @Mock PiiRedactor piiRedactor;

    static final QuestionRequest REQUEST  = new QuestionRequest("What is your experience?");
    static final Question        QUESTION = new Question("What is your experience?");

    @BeforeEach
    void setUp() {
        AppProperties.Rag rag = new AppProperties.Rag(3, 20, Duration.ofHours(1));
        AppProperties.Models models = new AppProperties.Models("primary-model", "fallback-model");
        when(appProperties.rag()).thenReturn(rag);
        when(appProperties.models()).thenReturn(models);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(promptCatalog.systemPrompt()).thenReturn(
                new PromptCatalog.Prompt("test system prompt", "v1", "abcd1234"));
        when(piiRedactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        aiUsageMetrics = new AiUsageMetrics(new SimpleMeterRegistry(), promptCatalog);
        chatService = new ChatService(chatClientBuilder, vectorStore, ragPipelineBulkhead,
                aiRateLimiter, chatMapper, redisTemplate, objectMapper, aiUsageMetrics,
                promptCatalog, piiRedactor, appProperties);
        when(chatMapper.toQuestion(REQUEST)).thenReturn(QUESTION);
    }

    private static ChatResponse stubChatResponse(String text) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("gemini-2.5-flash-lite")
                .usage(new DefaultUsage(10, 20, 30))
                .build();
        Generation generation = new Generation(new AssistantMessage(text));
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void answerBulkheadFullThrowsBulkheadFullException() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(false);
        when(ragPipelineBulkhead.getBulkheadConfig()).thenReturn(BulkheadConfig.ofDefaults());

        assertThatThrownBy(() -> chatService.answer(REQUEST))
                .isInstanceOf(BulkheadFullException.class);
    }

    @Test
    void answerRateLimitReachedReturnsRawFallback() {
        Document doc = new Document("SKILLS\nJava, Spring Boot");
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(doc));
        when(aiRateLimiter.acquirePermission()).thenReturn(false);
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("AI is currently unavailable");
        assertThat(result.answer()).contains("SKILLS");
        verify(ragPipelineBulkhead).onComplete();
    }

    @Test
    void answerAiCallSucceedsReturnsAiAnswer() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenReturn(stubChatResponse("Aleksandr has 5 years of Java experience."));
        AnswerResponse expected = new AnswerResponse("Aleksandr has 5 years of Java experience.");
        when(chatMapper.toResponse(any())).thenReturn(expected);

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void answerBothModelsFailReturnsRawFallback() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenThrow(new RuntimeException("AI unavailable"));
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("AI is currently unavailable");
        verify(ragPipelineBulkhead).onComplete();
    }

    @Test
    void answerPrimaryFailsFallbackSucceedsReturnsAiAnswer() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenThrow(new RuntimeException("primary model down"))
                .thenReturn(stubChatResponse("Fallback model answer."));
        AnswerResponse expected = new AnswerResponse("Fallback model answer.");
        when(chatMapper.toResponse(any())).thenReturn(expected);

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void answerNoRelevantDocsFallbackMentionsNoInfoFound() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(aiRateLimiter.acquirePermission()).thenReturn(false);
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        AnswerResponse result = chatService.answer(REQUEST);

        assertThat(result.answer()).contains("no relevant information");
        verify(ragPipelineBulkhead).onComplete();
    }

    @Test
    void answerBulkheadPermissionAlwaysReleasedEvenOnException() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> chatService.answer(REQUEST))
                .isInstanceOf(RuntimeException.class);
        verify(ragPipelineBulkhead).onComplete();
    }

    @Test
    void answerWithSessionSavesHistoryToRedisOnAiSuccess() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenReturn(stubChatResponse("AI answer."));
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        chatService.answerWithSession("session-1", REQUEST);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void answerWithSessionDoesNotSaveHistoryOnFallback() {
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(false);
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        chatService.answerWithSession("session-1", REQUEST);

        verify(valueOperations, org.mockito.Mockito.never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void answerWithSessionLoadsExistingHistoryFromRedis() throws Exception {
        String existingHistory = objectMapper.writeValueAsString(List.of(
                new java.util.LinkedHashMap<String, String>() {{ put("role", "user"); put("content", "Previous question"); }},
                new java.util.LinkedHashMap<String, String>() {{ put("role", "assistant"); put("content", "Previous answer"); }}
        ));
        when(valueOperations.get(anyString())).thenReturn(existingHistory);
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenReturn(stubChatResponse("AI answer."));
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        chatService.answerWithSession("session-1", REQUEST);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void answerWithSessionDuplicateQuestionSkipsHistorySave() throws Exception {
        String existingHistory = objectMapper.writeValueAsString(List.of(
                new java.util.LinkedHashMap<String, String>() {{ put("role", "user"); put("content", "What is your experience?"); }},
                new java.util.LinkedHashMap<String, String>() {{ put("role", "assistant"); put("content", "Previous answer"); }}
        ));
        when(valueOperations.get(anyString())).thenReturn(existingHistory);
        when(ragPipelineBulkhead.tryAcquirePermission()).thenReturn(true);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(new Document("context")));
        when(aiRateLimiter.acquirePermission()).thenReturn(true);
        when(chatClientBuilder.build().prompt()
                .system(anyString())
                .messages(ArgumentMatchers.<List<Message>>any())
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .options(any(org.springframework.ai.chat.prompt.ChatOptions.class))
                .call().chatResponse())
                .thenReturn(stubChatResponse("AI answer."));
        when(chatMapper.toResponse(any())).thenAnswer(inv ->
                new AnswerResponse(inv.<Answer>getArgument(0).text()));

        chatService.answerWithSession("session-1", REQUEST);

        verify(valueOperations, org.mockito.Mockito.never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void clearSessionDeletesRedisKey() {
        chatService.clearSession("session-1");

        verify(redisTemplate).delete("session:session-1");
    }
}
