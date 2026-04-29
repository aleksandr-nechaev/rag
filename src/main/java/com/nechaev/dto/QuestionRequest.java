package com.nechaev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionRequest(
        @Schema(description = "Question about Aleksandr's resume.",
                example = "What is Aleksandr's experience with Java?",
                maxLength = 1000)
        @NotBlank @Size(max = 1000) String question) {}
