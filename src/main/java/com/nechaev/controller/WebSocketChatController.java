package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

// Intentionally separate from RestApiChatController — different transport (WebSocket/STOMP vs HTTP).
@Controller
public class WebSocketChatController {

    private final ChatService chatService;

    public WebSocketChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/ask")
    @SendTo("/topic/answers")
    public AnswerResponse ask(@Valid QuestionRequest request) {
        // @Valid over STOMP may be silently skipped — guard explicitly.
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
        return chatService.answer(request);
    }
}
