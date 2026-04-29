package com.nechaev.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragResumeOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("RAG Resume API")
                .description("Question answering over Aleksandr Nechaev's resume via RAG (pgvector + Gemini).")
                .version("v1"));
    }
}
