package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Intentionally separate from WebSocketChatController — different transport (HTTP vs WebSocket/STOMP).
@RestController
@RequestMapping("/api/v1")
public class RestApiChatController {

    private final ChatService chatService;

    public RestApiChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/ask")
    public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
        return chatService.answer(request);
    }
}
