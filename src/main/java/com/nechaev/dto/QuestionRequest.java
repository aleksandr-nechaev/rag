package com.nechaev.dto;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(@NotBlank String question) {}
