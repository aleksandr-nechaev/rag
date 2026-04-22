package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.service.ChatService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import com.nechaev.exception.RateLimitExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RestApiChatController.class, RateLimitExceptionHandler.class})
class RestApiChatControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ChatService chatService;

    @Test
    void askValidRequestReturns200WithAnswer() throws Exception {
        when(chatService.answer(any())).thenReturn(new AnswerResponse("Aleksandr has 5 years of Java experience."));

        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "What is your Java experience?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Aleksandr has 5 years of Java experience."));
    }

    @Test
    void askBlankQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askMissingQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askBulkheadFullReturns429() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(0).build());
        when(chatService.answer(any()))
                .thenThrow(BulkheadFullException.createBulkheadFullException(bulkhead));

        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "What is your experience?"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Server is busy, please try again later."));
    }

    @Test
    void askRateLimitExceededReturns429() throws Exception {
        RateLimiter rateLimiter = RateLimiter.of("test", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(java.time.Duration.ofSeconds(60)).build());
        when(chatService.answer(any()))
                .thenThrow(RequestNotPermitted.createRequestNotPermitted(rateLimiter));

        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "What is your experience?"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many requests, please try again in a few seconds."));
    }
}
