package com.beingbmc.aigateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs (with all credentials masked) the values of the most important
 * runtime properties so we can verify env-var injection in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDiagnostics {

    private final Environment env;

    @EventListener(ApplicationReadyEvent.class)
    public void logResolvedConfig() {
        log.info("[startup] spring.data.mongodb.uri = {}",
                maskMongoUri(env.getProperty("spring.data.mongodb.uri")));
        log.info("[startup] SPRING_DATA_MONGODB_URI env var present = {}",
                System.getenv("SPRING_DATA_MONGODB_URI") != null);
        log.info("[startup] spring.ai.openai.api-key length = {}",
                lengthOf(env.getProperty("spring.ai.openai.api-key")));
        log.info("[startup] spring.ai.openai.chat.options.model = {}",
                env.getProperty("spring.ai.openai.chat.options.model"));
    }

    private static String maskMongoUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "<NOT SET>";
        }
        // Strip credentials between scheme and @
        int schemeEnd = uri.indexOf("://");
        int at = uri.indexOf('@');
        if (schemeEnd > 0 && at > schemeEnd) {
            return uri.substring(0, schemeEnd + 3) + "***:***@" + uri.substring(at + 1);
        }
        return uri;
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }
}
