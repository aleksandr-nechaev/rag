package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

// Intentionally separate from RestApiChatController — different transport (WebSocket/STOMP vs HTTP).
@Controller
public class WebSocketChatController {

    private static final Logger log = LoggerFactory.getLogger(WebSocketChatController.class);

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketChatController(ChatService chatService, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/ask")
    public void ask(@Valid QuestionRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        if (sessionId == null) {
            log.warn("WebSocket ask received with no session, dropping.");
            return;
        }
        AnswerResponse response;
        try {
            response = chatService.answerWithSession(sessionId, request);
        } catch (BulkheadFullException | RequestNotPermitted e) {
            response = new AnswerResponse("Server is busy, please try again later.");
        } catch (Exception e) {
            log.error("WebSocket ask failed for session {}…",
                    sessionId.substring(0, Math.min(8, sessionId.length())), e);
            response = new AnswerResponse("Something went wrong. Please try again.");
        }

        SimpMessageHeaderAccessor replyAccessor = SimpMessageHeaderAccessor.create();
        replyAccessor.setSessionId(sessionId);
        replyAccessor.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/answers", response, replyAccessor.getMessageHeaders());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) return;
        chatService.clearSession(sessionId);
    }
}
