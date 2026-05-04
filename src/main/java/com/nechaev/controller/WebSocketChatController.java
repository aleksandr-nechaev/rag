package com.nechaev.controller;

import com.nechaev.config.HttpSessionHandshakeInterceptor;
import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import com.nechaev.util.LogUtils;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.stream.Collectors;

// Intentionally separate from RestApiChatController — different transport (WebSocket/STOMP vs HTTP).
@Controller
public class WebSocketChatController {

    private static final Logger log = LoggerFactory.getLogger(WebSocketChatController.class);
    private static final String REPLY_DESTINATION = "/queue/v1/answers";

    private final ChatService chatService;

    public WebSocketChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // broadcast = false routes to the originating session only (no Principal in this app).
    // sessionId comes from the Spring-Session HttpSession bound at handshake (cookie-based,
    // distributed via Redis) — survives reconnects and is the same on any instance behind LB.
    @MessageMapping("/v1/ask")
    @SendToUser(value = REPLY_DESTINATION, broadcast = false)
    public AnswerResponse ask(@Valid @Payload QuestionRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = (String) headerAccessor.getSessionAttributes()
                .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR);
        if (sessionId == null) {
            log.warn("WebSocket ask received with no HTTP session id, dropping.");
            return null; // null return: @SendToUser skips dispatch
        }
        return chatService.answerWithSession(sessionId, request);
    }

    @MessageExceptionHandler({BulkheadFullException.class, RequestNotPermitted.class})
    @SendToUser(value = REPLY_DESTINATION, broadcast = false)
    public AnswerResponse handleBusy() {
        return new AnswerResponse("Server is busy, please try again later.");
    }

    // Field details kept in logs only — never echo raw payload back via getMessage(),
    // which would include rejected user input (mirrors ApiExceptionHandler.handleValidation).
    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(value = REPLY_DESTINATION, broadcast = false)
    public AnswerResponse handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.info("WebSocket request validation failed: {}", details.isEmpty() ? "no field errors" : details);
        return new AnswerResponse("Invalid request.");
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = REPLY_DESTINATION, broadcast = false)
    public AnswerResponse handleUnexpected(Exception e, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = (String) headerAccessor.getSessionAttributes()
                .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR);
        log.error("WebSocket ask failed for session {}…", LogUtils.shortId(sessionId), e);
        return new AnswerResponse("Something went wrong. Please try again.");
    }
}
