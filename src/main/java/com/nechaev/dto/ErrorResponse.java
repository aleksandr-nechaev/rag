package com.nechaev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ErrorResponse(
        @Schema(description = "Human-readable error message.",
                example = "Too many requests, please try again in a few seconds.")
        String error) {}
