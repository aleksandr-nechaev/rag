package com.nechaev.controller;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.ErrorResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Intentionally separate from WebSocketChatController — different transport (HTTP vs WebSocket/STOMP).
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chat", description = "Resume Q&A over HTTP.")
public class RestApiChatController {

    private final ChatService chatService;

    public RestApiChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(
            summary = "Ask a question about the resume",
            description = "Runs RAG over the resume and returns an AI-generated answer. "
                    + "May return a raw fallback if the AI is rate-limited or unavailable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer generated."),
            @ApiResponse(responseCode = "400", description = "Validation failed (blank or oversized question).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit or bulkhead saturated.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/ask")
    public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
        return chatService.answer(request);
    }
}
