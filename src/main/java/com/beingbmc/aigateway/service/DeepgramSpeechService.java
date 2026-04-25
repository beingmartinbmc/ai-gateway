package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import com.beingbmc.aigateway.dto.TtsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepgramSpeechService {

    private static final int WAV_SAMPLE_RATE = 24_000;
    private static final String MODEL_PATTERN = "[A-Za-z0-9._-]{1,80}";

    private final WebClient.Builder webClientBuilder;
    private final AiGatewayProperties props;

    public Mono<SpeechAudio> synthesize(TtsRequest request) {
        return synthesize(request.text(), request.model(), parseFormat(request.audioFormat()));
    }

    public Mono<SpeechAudio> synthesize(String text, String modelOverride, AudioFormat format) {
        AiGatewayProperties.Tts tts = props.getTts();
        AiGatewayProperties.Deepgram deepgram = tts.getDeepgram();
        String cleanedText = cleanTextForSpeech(validateText(text, tts.getMaxTextChars()));
        String model = resolveModel(modelOverride, deepgram.getModel());

        if (isBlank(deepgram.getApiKey())) {
            return Mono.error(new SpeechConfigurationException("Voice API key is not configured"));
        }
        if (cleanedText.length() < 3) {
            return Mono.error(new IllegalArgumentException("'text' does not contain enough speakable content"));
        }

        URI uri = deepgramUri(deepgram.getBaseUrl(), model, format);
        WebClient client = webClientBuilder.build();
        return client.post()
                .uri(uri)
                .headers(headers -> {
                    headers.set("Authorization", "Token " + deepgram.getApiKey());
                    headers.setContentType(MediaType.TEXT_PLAIN);
                })
                .bodyValue(cleanedText)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                        .then(Mono.error(new UpstreamSpeechException("Deepgram failed to generate speech"))))
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(Math.max(1, deepgram.getTimeoutSeconds())))
                .map(bytes -> new SpeechAudio(bytes, format.mediaType(), format.fileExtension(), cleanedText));
    }

    public String cleanTextForSpeech(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("!\\[([^]]*)]\\([^)]+\\)", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replaceAll("[*_~#>\\[\\]()]"," ")
                .replaceAll("\\p{So}", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public AudioFormat parseFormat(String value) {
        if (isBlank(value)) {
            return AudioFormat.MP3;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "mp3" -> AudioFormat.MP3;
            case "wav", "linear16" -> AudioFormat.WAV;
            default -> throw new IllegalArgumentException("'audioFormat' must be 'mp3' or 'wav'");
        };
    }

    private String validateText(String text, int maxTextChars) {
        if (isBlank(text)) {
            throw new IllegalArgumentException("'text' must not be empty");
        }
        String trimmed = text.trim();
        if (trimmed.length() > maxTextChars) {
            throw new IllegalArgumentException("'text' must be at most " + maxTextChars + " characters");
        }
        return trimmed;
    }

    private String resolveModel(String override, String configuredModel) {
        String model = isBlank(override) ? configuredModel : override.trim();
        if (isBlank(model)) {
            throw new SpeechConfigurationException("Deepgram voice model is not configured");
        }
        if (!model.matches(MODEL_PATTERN)) {
            throw new IllegalArgumentException("'model' contains unsupported characters");
        }
        return model;
    }

    private URI deepgramUri(String baseUrl, String model, AudioFormat format) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("model", model)
                .queryParam("encoding", format.deepgramEncoding());
        if (format == AudioFormat.WAV) {
            builder.queryParam("sample_rate", WAV_SAMPLE_RATE);
        }
        return builder.build(true).toUri();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum AudioFormat {
        MP3("mp3", MediaType.valueOf("audio/mpeg"), "mp3"),
        WAV("linear16", MediaType.valueOf("audio/wav"), "wav");

        private final String deepgramEncoding;
        private final MediaType mediaType;
        private final String fileExtension;

        AudioFormat(String deepgramEncoding, MediaType mediaType, String fileExtension) {
            this.deepgramEncoding = deepgramEncoding;
            this.mediaType = mediaType;
            this.fileExtension = fileExtension;
        }

        public String deepgramEncoding() {
            return deepgramEncoding;
        }

        public MediaType mediaType() {
            return mediaType;
        }

        public String fileExtension() {
            return fileExtension;
        }
    }

    public record SpeechAudio(byte[] bytes, MediaType mediaType, String fileExtension, String spokenText) {
    }

    public static class SpeechConfigurationException extends RuntimeException {
        public SpeechConfigurationException(String message) {
            super(message);
        }
    }

    public static class UpstreamSpeechException extends RuntimeException {
        public UpstreamSpeechException(String message) {
            super(message);
        }
    }
}
