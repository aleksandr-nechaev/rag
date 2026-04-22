package com.nechaev.config;

import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public TransformersEmbeddingModel embeddingModel() {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2");
        model.setTokenizerResource("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2");
        return model;
    }
}
