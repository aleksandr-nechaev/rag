package com.nechaev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AnswerResponse(
        @Schema(description = "Answer generated from the resume context.",
                example = "Aleksandr has over 5 years of Java experience...")
        String answer) {}
