package com.nechaev.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiUsageMetrics {

    private static final Logger log = LoggerFactory.getLogger(AiUsageMetrics.class);
    private static final String UNKNOWN_MODEL = "unknown";

    public enum Outcome { AI, FALLBACK_RATE_LIMIT, FALLBACK_ERROR }

    public enum TokenType {
        PROMPT, COMPLETION, TOTAL;

        private String tag() { return name().toLowerCase(); }
    }

    private record TokenMeterKey(String model, TokenType type) {}

    private final MeterRegistry registry;
    private final Map<TokenMeterKey, Counter> tokenCounters = new ConcurrentHashMap<>();
    private final Map<TokenMeterKey, DistributionSummary> tokenDistributions = new ConcurrentHashMap<>();
    private final Map<Outcome, Counter> outcomeCounters = new ConcurrentHashMap<>();

    public AiUsageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordTokenUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response != null ? response.getMetadata() : null;
        if (metadata == null) return;
        Usage usage = metadata.getUsage();
        if (usage == null) return;
        String model = metadata.getModel() != null ? metadata.getModel() : UNKNOWN_MODEL;
        int prompt = nz(usage.getPromptTokens());
        int completion = nz(usage.getCompletionTokens());
        int total = nz(usage.getTotalTokens(), prompt + completion);
        recordTokens(model, TokenType.PROMPT, prompt);
        recordTokens(model, TokenType.COMPLETION, completion);
        recordTokens(model, TokenType.TOTAL, total);
        log.info("ai.usage model={} prompt={} completion={} total={}", model, prompt, completion, total);
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static int nz(Integer i, int fallback) {
        return i == null ? fallback : i;
    }

    public void recordOutcome(Outcome outcome) {
        outcomeCounters.computeIfAbsent(outcome, o ->
                Counter.builder("ai.requests").tag("outcome", o.name().toLowerCase()).register(registry)
        ).increment();
    }

    private void recordTokens(String model, TokenType type, int tokens) {
        if (tokens <= 0) return;
        TokenMeterKey key = new TokenMeterKey(model, type);
        tokenCounters.computeIfAbsent(key, k ->
                Counter.builder("ai.tokens")
                        .tag("type", k.type.tag())
                        .tag("model", k.model)
                        .register(registry)
        ).increment(tokens);
        tokenDistributions.computeIfAbsent(key, k ->
                DistributionSummary.builder("ai.tokens.per_request")
                        .tag("type", k.type.tag())
                        .tag("model", k.model)
                        .publishPercentiles(0.5, 0.95)
                        .register(registry)
        ).record(tokens);
    }
}
