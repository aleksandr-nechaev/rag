package com.nechaev.service;

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
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that answers questions about Aleksandr Nechaev \
            based strictly on the provided resume context. \
            If the context does not contain enough information to answer, say so honestly. \
            Answer concisely and professionally.""";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final Bulkhead databaseBulkhead;
    private final RateLimiter aiRateLimiter;
    private final AnswerCacheService answerCache;
    private final ChatMapper chatMapper;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       Bulkhead databaseBulkhead,
                       RateLimiter aiRateLimiter,
                       AnswerCacheService answerCache,
                       ChatMapper chatMapper) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.databaseBulkhead = databaseBulkhead;
        this.aiRateLimiter = aiRateLimiter;
        this.answerCache = answerCache;
        this.chatMapper = chatMapper;
    }

    public AnswerResponse answer(QuestionRequest request) {
        Question question = chatMapper.toQuestion(request);
        return answerCache.find(question)
                .map(chatMapper::toResponse)
                .orElseGet(() -> slowPath(question));
    }

    // Vector search → AI (or raw fallback). Always caches the result
    // so the next identical question never reaches the DB again.
    private AnswerResponse slowPath(Question question) {
        if (!databaseBulkhead.tryAcquirePermission()) {
            throw BulkheadFullException.createBulkheadFullException(databaseBulkhead);
        }
        try {
            List<Document> relevant = vectorStore.similaritySearch(
                    SearchRequest.builder().query(question.text()).topK(3).build());

            Answer answer;
            if (!aiRateLimiter.acquirePermission()) {
                log.info("AI rate limit reached, using raw fallback for question.");
                answer = rawFallback(relevant);
            } else {
                String context = relevant.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n---\n"));
                log.debug("Calling AI.\nQuestion: {}\nRelevant chunks ({}):\n{}", question.text(), relevant.size(), context);
                try {
                    answer = callAi(question, context);
                } catch (Exception e) {
                    log.warn("AI call failed, using raw fallback: {}", e.getMessage(), e);
                    answer = rawFallback(relevant);
                }
            }

            answerCache.store(question, answer);
            return chatMapper.toResponse(answer);
        } finally {
            databaseBulkhead.onComplete();
        }
    }

    private Answer rawFallback(List<Document> relevant) {
        if (relevant.isEmpty()) {
            return new Answer("AI is currently unavailable and no relevant information was found in the resume.");
        }
        return new Answer("AI is currently unavailable. Here is the relevant information from the resume:\n\n"
                + relevant.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n")));
    }

    private Answer callAi(Question question, String context) {
        String result = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text("""
                        Resume context:
                        {context}

                        Question: {question}
                        """)
                        .param("context", context)
                        .param("question", question.text()))
                .call()
                .content();
        return new Answer(result != null ? result : "No response from AI.");
    }
}
