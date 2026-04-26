package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import com.beingbmc.aigateway.dto.OpenAiProxyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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
        return forward(request);
    }

    public Mono<String> completeWithImages(OpenAiProxyRequest request, List<FilePart> images) {
        validateRequest(request);
        if (images == null || images.isEmpty()) {
            return forward(request);
        }
        return imageContentParts(images)
                .map(imageParts -> withImagesOnLastUserMessage(request, imageParts))
                .flatMap(this::forward);
    }

    private Mono<String> forward(OpenAiProxyRequest request) {
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
            if (message == null || isBlank(message.role()) || isBlankContent(message.content())) {
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

    private Mono<List<Map<String, Object>>> imageContentParts(List<FilePart> images) {
        return Flux.fromIterable(images)
                .map(this::validateImagePart)
                .flatMap(this::toImageContentPart)
                .collectList();
    }

    private FilePart validateImagePart(FilePart image) {
        MediaType contentType = image.headers().getContentType();
        if (contentType == null || !"image".equalsIgnoreCase(contentType.getType())) {
            throw new IllegalArgumentException("Only image file parts are supported");
        }
        return image;
    }

    private Mono<Map<String, Object>> toImageContentPart(FilePart image) {
        MediaType contentType = image.headers().getContentType();
        return DataBufferUtils.join(image.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    String dataUrl = "data:" + contentType + ";base64,"
                            + Base64.getEncoder().encodeToString(bytes);
                    return Map.of(
                            "type", "image_url",
                            "image_url", Map.of("url", dataUrl));
                });
    }

    private OpenAiProxyRequest withImagesOnLastUserMessage(OpenAiProxyRequest request,
                                                           List<Map<String, Object>> imageParts) {
        List<OpenAiProxyRequest.Message> messages = new ArrayList<>(request.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            OpenAiProxyRequest.Message message = messages.get(i);
            if ("user".equalsIgnoreCase(message.role())) {
                List<Object> contentParts = contentParts(message.content());
                contentParts.addAll(imageParts);
                messages.set(i, new OpenAiProxyRequest.Message(message.role(), contentParts));
                return new OpenAiProxyRequest(
                        messages,
                        request.model(),
                        request.maxTokens(),
                        request.temperature(),
                        request.topP(),
                        request.frequencyPenalty(),
                        request.presencePenalty());
            }
        }
        throw new IllegalArgumentException("At least one user message is required");
    }

    private List<Object> contentParts(Object content) {
        List<Object> parts = new ArrayList<>();
        if (content instanceof List<?> existingParts) {
            parts.addAll(existingParts);
        } else if (content instanceof String text && !text.isBlank()) {
            parts.add(Map.of("type", "text", "text", text));
        } else {
            parts.add(content);
        }
        return parts;
    }

    private boolean isBlankContent(Object content) {
        if (content == null) {
            return true;
        }
        if (content instanceof String text) {
            return text.isBlank();
        }
        if (content instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
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
