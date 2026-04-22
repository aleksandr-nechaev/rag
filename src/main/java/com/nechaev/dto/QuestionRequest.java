package com.nechaev.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionRequest(@NotBlank @Size(max = 1000) String question) {}
