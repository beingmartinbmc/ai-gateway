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
    private Tts tts = new Tts();
    private VoiceStream voiceStream = new VoiceStream();
    private OpenAiProxy openAiProxy = new OpenAiProxy();

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
        private int capacity = 10;
        private int refillTokens = 10;
        private int refillPeriodSeconds = 60;
    }

    @Getter
    @Setter
    public static class Tts {
        private int maxTextChars = 5000;
        private Deepgram deepgram = new Deepgram();
    }

    @Getter
    @Setter
    public static class Deepgram {
        private String apiKey = "";
        private String model = "aura-2-draco-en";
        private String baseUrl = "https://api.deepgram.com/v1/speak";
        private int timeoutSeconds = 30;
    }

    @Getter
    @Setter
    public static class VoiceStream {
        private int maxMessageChars = 4000;
        private int chunkSize = 30;
        private int minChunkSize = 15;
        private int maxChunkSize = 60;
        private int maxAudioChunks = 12;
    }

    @Getter
    @Setter
    public static class OpenAiProxy {
        private String url = "https://api.openai.com/v1/chat/completions";
        private String model = "gpt-5-nano";
        private int maxTokens = 1000;
        private double temperature = 0.8;
        private double topP = 0.85;
        private double frequencyPenalty = 0.4;
        private double presencePenalty = 0.2;
        private int timeoutSeconds = 120;
    }
}
