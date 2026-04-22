package com.nechaev.config;

import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public TransformersEmbeddingModel embeddingModel() {
        // Uses all-MiniLM-L6-v2 (384 dimensions), downloaded on first run
        return new TransformersEmbeddingModel();
    }
}
