package com.beingbmc.aigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, AiGatewayProperties props) {
        return builder
                .defaultSystem(props.getSystemPrompt())
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    /**
     * In-memory vector store used purely as a semantic cache.
     * Lives only for the JVM lifetime and remains the default for local development.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai-gateway.cache", name = "store", havingValue = "in-memory", matchIfMissing = true)
    public VectorStore semanticCacheVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-gateway.cache", name = "store", havingValue = "supabase")
    public VectorStore supabaseSemanticCacheVectorStore(EmbeddingModel embeddingModel,
                                                        AiGatewayProperties props) {
        AiGatewayProperties.Supabase supabase = props.getCache().getSupabase();
        validateSupabaseConfig(supabase);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(supabase.getJdbcUrl());
        dataSource.setUsername(supabase.getUsername());
        dataSource.setPassword(supabase.getPassword());

        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .dimensions(supabase.getDimensions())
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(supabase.isInitializeSchema())
                .schemaName(supabase.getSchemaName())
                .vectorTableName(supabase.getTableName())
                .build();
    }

    private static void validateSupabaseConfig(AiGatewayProperties.Supabase supabase) {
        if (isBlank(supabase.getJdbcUrl()) || isBlank(supabase.getUsername()) || isBlank(supabase.getPassword())) {
            throw new IllegalStateException(
                    "Supabase cache requires SUPABASE_DB_JDBC_URL, SUPABASE_DB_USERNAME, and SUPABASE_DB_PASSWORD");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
