package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import com.beingbmc.aigateway.dto.OpenAiProxyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class OpenAiProxyService {

    private static final String PROXY_MODEL = "gpt-4.1-nano";
    private static final Set<String> ALLOWED_ROLES = Set.of("system", "developer", "user", "assistant");

    private final WebClient webClient;
    private final AiGatewayProperties props;
    private final String apiKey;

    public OpenAiProxyService(WebClient.Builder webClientBuilder,
                              AiGatewayProperties props,
                              @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.webClient = webClientBuilder.build();
        this.props = props;
        this.apiKey = apiKey;
    }

    public Mono<String> complete(OpenAiProxyRequest request) {
        validateRequest(request);
        AiGatewayProperties.OpenAiProxy proxy = props.getOpenAiProxy();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", PROXY_MODEL);
        body.put("messages", request.messages());
        body.put("max_tokens", positiveOrDefault(request.maxTokens(), proxy.getMaxTokens()));
        body.put("temperature", numberOrDefault(request.temperature(), proxy.getTemperature()));
        body.put("top_p", numberOrDefault(request.topP(), proxy.getTopP()));
        body.put("frequency_penalty", numberOrDefault(request.frequencyPenalty(), proxy.getFrequencyPenalty()));
        body.put("presence_penalty", numberOrDefault(request.presencePenalty(), proxy.getPresencePenalty()));

        if (isBlank(apiKey)) {
            return Mono.error(new OpenAiProxyConfigurationException("OpenAI API key is not configured"));
        }

        return webClient.post()
                .uri(proxy.getUrl())
                .headers(headers -> {
                    headers.setBearerAuth(apiKey);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(errorBody -> Mono.error(new OpenAiProxyUpstreamException(
                                "OpenAI API error: " + response.statusCode().value(), errorBody))))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(Math.max(1, proxy.getTimeoutSeconds())));
    }

    private void validateRequest(OpenAiProxyRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("Messages array is required and must not be empty");
        }
        boolean hasUser = false;
        for (OpenAiProxyRequest.Message message : request.messages()) {
            if (message == null || isBlank(message.role()) || isBlank(message.content())) {
                throw new IllegalArgumentException("Each message must have role and content properties");
            }
            String role = message.role().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_ROLES.contains(role)) {
                throw new IllegalArgumentException("Unsupported message role: " + message.role());
            }
            if ("user".equals(role)) {
                hasUser = true;
            }
        }
        if (!hasUser) {
            throw new IllegalArgumentException("At least one user message is required");
        }
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        int resolved = value != null ? value : defaultValue;
        if (resolved < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        return resolved;
    }

    private double numberOrDefault(Double value, double defaultValue) {
        return value != null ? value : defaultValue;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class OpenAiProxyConfigurationException extends RuntimeException {
        public OpenAiProxyConfigurationException(String message) {
            super(message);
        }
    }

    public static class OpenAiProxyUpstreamException extends RuntimeException {
        private final String responseBody;

        public OpenAiProxyUpstreamException(String message, String responseBody) {
            super(message);
            this.responseBody = responseBody;
        }

        public String responseBody() {
            return responseBody;
        }
    }
}
