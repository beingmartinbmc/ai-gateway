package com.beingbmc.aigateway.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, AiGatewayProperties props) {
        return builder
                .defaultSystem(props.getSystemPrompt())
                .build();
    }

    /**
     * In-memory vector store used purely as a semantic cache.
     * Lives only for the JVM lifetime — perfect for the user's "in-memory caching" requirement.
     */
    @Bean
    public VectorStore semanticCacheVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
