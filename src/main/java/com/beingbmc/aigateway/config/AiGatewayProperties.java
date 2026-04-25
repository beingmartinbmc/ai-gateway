package com.beingbmc.aigateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai-gateway")
public class AiGatewayProperties {

    private String systemPrompt = "";
    private Cache cache = new Cache();
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class Cache {
        private boolean enabled = true;
        private Store store = Store.IN_MEMORY;
        private double similarityThreshold = 0.92;
        private int maxEntries = 500;
        /** Cached entries older than this are evicted. {@code 0} disables TTL. */
        private long ttlSeconds = 3600;
        private boolean skipWhenAttachment = true;
        private Supabase supabase = new Supabase();
    }

    public enum Store {
        IN_MEMORY,
        SUPABASE
    }

    @Getter
    @Setter
    public static class Supabase {
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private boolean initializeSchema = true;
        private String schemaName = "public";
        private String tableName = "semantic_cache";
        private int dimensions = 1536;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int capacity = 30;
        private int refillTokens = 30;
        private int refillPeriodSeconds = 60;
    }
}
