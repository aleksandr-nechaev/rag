package com.nechaev.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nechaev.config.AppProperties;
import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.mapper.ChatMapper;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static com.nechaev.service.AiUsageMetrics.Outcome.AI;
import static com.nechaev.service.AiUsageMetrics.Outcome.AI_FALLBACK_MODEL;
import static com.nechaev.service.AiUsageMetrics.Outcome.FALLBACK_ERROR;
import static com.nechaev.service.AiUsageMetrics.Outcome.FALLBACK_RATE_LIMIT;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SESSION_KEY_PREFIX = "session:";
    // Cap on individual session message content stored in Redis. User input is bounded
    // separately by QuestionRequest @Size(max=1000); assistant replies can be longer, hence 2000.
    private static final int MAX_MESSAGE_CONTENT_LENGTH = 2000;
    private static final String AI_EMPTY_RESPONSE = "No response from AI.";
    private static final String AI_UNAVAILABLE_NO_CONTEXT =
            "AI is currently unavailable and no relevant information was found in the resume.";
    private static final String AI_UNAVAILABLE_WITH_CONTEXT =
            "AI is currently unavailable. Here is the relevant information from the resume:\n\n";
    private static final TypeReference<List<MessageDto>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final Bulkhead ragPipelineBulkhead;
    private final RateLimiter aiRateLimiter;
    private final ChatMapper chatMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiUsageMetrics aiUsageMetrics;
    private final PromptCatalog promptCatalog;
    private final PiiRedactor piiRedactor;
    private final String primaryModel;
    private final String fallbackModel;
    private final int topK;
    private final int maxHistory;
    private final Duration sessionTtl;

    private enum Role {
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT
    }

    private record MessageDto(Role role, String content) {}

    private record PipelineResult(AnswerResponse response, boolean aiGenerated) {}

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       Bulkhead ragPipelineBulkhead,
                       RateLimiter aiRateLimiter,
                       ChatMapper chatMapper,
                       StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper,
                       AiUsageMetrics aiUsageMetrics,
                       PromptCatalog promptCatalog,
                       PiiRedactor piiRedactor,
                       AppProperties appProperties) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.ragPipelineBulkhead = ragPipelineBulkhead;
        this.aiRateLimiter = aiRateLimiter;
        this.chatMapper = chatMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.aiUsageMetrics = aiUsageMetrics;
        this.promptCatalog = promptCatalog;
        this.piiRedactor = piiRedactor;
        this.primaryModel = appProperties.models().primary();
        this.fallbackModel = appProperties.models().fallback();
        this.topK = appProperties.rag().topK();
        this.maxHistory = appProperties.rag().maxHistory();
        this.sessionTtl = appProperties.rag().sessionTtl();
    }

    @Cacheable(value = "answers", keyGenerator = "questionKeyGenerator")
    public AnswerResponse answer(QuestionRequest request) {
        Question question = chatMapper.toQuestion(request);
        return executeRagPipeline(question, List.of()).response();
    }

    public AnswerResponse answerWithSession(String sessionId, QuestionRequest request) {
        LinkedList<Message> history = loadHistory(sessionId);
        Question question = chatMapper.toQuestion(request);
        PipelineResult result = executeRagPipeline(question, history);
        AnswerResponse response = result.response();
        if (result.aiGenerated() && !isDuplicateOfLastQuestion(history, request.question())) {
            history.add(new UserMessage(request.question()));
            history.add(new AssistantMessage(response.answer()));
            trimToMaxHistory(history);
            saveHistory(sessionId, history);
        }
        return response;
    }

    private boolean isDuplicateOfLastQuestion(LinkedList<Message> history, String question) {
        return history.size() >= 2
                && history.getLast() instanceof AssistantMessage
                && history.get(history.size() - 2) instanceof UserMessage um
                && um.getText().equals(question);
    }

    private void trimToMaxHistory(LinkedList<Message> history) {
        while (history.size() > maxHistory) {
            history.removeFirst();
            history.removeFirst();
        }
    }

    public void clearSession(String sessionId) {
        redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
    }

    private LinkedList<Message> loadHistory(String sessionId) {
        String json = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        if (json == null) return new LinkedList<>();
        try {
            List<MessageDto> dtos = objectMapper.readValue(json, MESSAGE_LIST_TYPE);
            return dtos.stream()
                    .map(dto -> switch (dto.role()) {
                        case null -> throw new IllegalArgumentException("Null role in session history");
                        case USER -> (Message) new UserMessage(dto.content());
                        case ASSISTANT -> new AssistantMessage(dto.content());
                    })
                    .collect(Collectors.toCollection(LinkedList::new));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Failed to deserialize session history for session {}…, starting fresh: {}",
                    sessionId.substring(0, Math.min(8, sessionId.length())), e.getMessage());
            return new LinkedList<>();
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_MESSAGE_CONTENT_LENGTH ? s : s.substring(0, MAX_MESSAGE_CONTENT_LENGTH);
    }

    private static Role toRole(Message message) {
        return switch (message) {
            case UserMessage _ -> Role.USER;
            case AssistantMessage _ -> Role.ASSISTANT;
            default -> throw new IllegalStateException(
                    "Unsupported message type in session history: " + message.getClass());
        };
    }

    private void saveHistory(String sessionId, List<Message> history) {
        List<MessageDto> dtos = history.stream()
                .map(m -> new MessageDto(toRole(m), truncate(m.getText())))
                .toList();
        try {
            String json = objectMapper.writeValueAsString(dtos);
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, json, sessionTtl);
        } catch (JacksonException e) {
            log.warn("Failed to serialize session history for session {}…: {}",
                    sessionId.substring(0, Math.min(8, sessionId.length())), e.getMessage());
        }
    }

    private PipelineResult executeRagPipeline(Question question, List<Message> history) {
        if (!ragPipelineBulkhead.tryAcquirePermission()) {
            throw BulkheadFullException.createBulkheadFullException(ragPipelineBulkhead);
        }
        try {
            List<Document> relevant = vectorStore.similaritySearch(
                    SearchRequest.builder().query(question.text()).topK(topK).build());
            return callAiOrFallback(question, relevant, history);
        } finally {
            ragPipelineBulkhead.onComplete();
        }
    }

    private PipelineResult callAiOrFallback(Question question, List<Document> relevant, List<Message> history) {
        if (!aiRateLimiter.acquirePermission()) {
            log.info("AI rate limit reached, using raw fallback for question.");
            aiUsageMetrics.recordOutcome(FALLBACK_RATE_LIMIT);
            return new PipelineResult(chatMapper.toResponse(rawFallback(relevant)), false);
        }
        String context = relevant.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        log.debug("Calling AI with {} relevant chunks (question length: {} chars).",
                relevant.size(), question.text().length());
        // Single rate-limit permit covers both attempts: one user-request = one slot, regardless
        // of how many models we fall through. Tracks cost on our side, not Google's per-model quota.
        try {
            Answer answer = callAi(question, context, history, primaryModel);
            aiUsageMetrics.recordOutcome(AI);
            return new PipelineResult(chatMapper.toResponse(answer), true);
        } catch (Exception primaryError) {
            // INFO, not WARN: with limit-for-period sized above primary's RPM, fallback firing is
            // expected steady-state behaviour, not an alert condition. WARN is reserved for the
            // case below where both models fail.
            log.info("Primary model '{}' failed, attempting fallback '{}': {}",
                    primaryModel, fallbackModel, primaryError.getMessage());
            try {
                Answer answer = callAi(question, context, history, fallbackModel);
                aiUsageMetrics.recordOutcome(AI_FALLBACK_MODEL);
                return new PipelineResult(chatMapper.toResponse(answer), true);
            } catch (Exception fallbackError) {
                log.warn("Fallback model '{}' also failed, using raw fallback: {}",
                        fallbackModel, fallbackError.getMessage(), fallbackError);
                aiUsageMetrics.recordOutcome(FALLBACK_ERROR);
                return new PipelineResult(chatMapper.toResponse(rawFallback(relevant)), false);
            }
        }
    }

    private Answer rawFallback(List<Document> relevant) {
        if (relevant.isEmpty()) {
            return new Answer(AI_UNAVAILABLE_NO_CONTEXT);
        }
        String chunks = relevant.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        return new Answer(AI_UNAVAILABLE_WITH_CONTEXT + piiRedactor.redact(chunks));
    }

    private Answer callAi(Question question, String context, List<Message> history, String model) {
        ChatResponse response = chatClient.prompt()
                .system(promptCatalog.systemPrompt().text())
                .messages(history)
                .user(u -> u.text("""
                        Resume context:
                        {context}

                        Question: {question}
                        """)
                        .param("context", context)
                        .param("question", question.text()))
                .options(ChatOptions.builder().model(model).build())
                .call()
                .chatResponse();
        if (response == null) return new Answer(AI_EMPTY_RESPONSE);
        Generation generation = response.getResult();
        if (generation == null) return new Answer(AI_EMPTY_RESPONSE);
        AssistantMessage output = generation.getOutput();
        if (output == null) return new Answer(AI_EMPTY_RESPONSE);
        aiUsageMetrics.recordTokenUsage(response);
        String text = piiRedactor.redact(output.getText());
        return new Answer(text != null ? text : AI_EMPTY_RESPONSE);
    }
}
