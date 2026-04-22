package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

// Intentionally separate from RestApiChatController — different transport (WebSocket/STOMP vs HTTP).
@Controller
public class WebSocketChatController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketChatController(ChatService chatService, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/ask")
    public void ask(@Valid QuestionRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        AnswerResponse response = chatService.answer(request);

        SimpMessageHeaderAccessor replyAccessor = SimpMessageHeaderAccessor.create();
        replyAccessor.setSessionId(sessionId);
        replyAccessor.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/answers", response, replyAccessor.getMessageHeaders());
    }
}
