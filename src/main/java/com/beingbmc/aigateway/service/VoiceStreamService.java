package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import com.beingbmc.aigateway.dto.VoiceStreamRequest;
import com.beingbmc.aigateway.dto.VoiceStreamRequest.VoiceSettings;
import com.beingbmc.aigateway.service.DeepgramSpeechService.AudioFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceStreamService {

    private final ChatClient chatClient;
    private final DeepgramSpeechService speechService;
    private final AiGatewayProperties props;

    public Flux<ServerSentEvent<Map<String, Object>>> stream(VoiceStreamRequest request) {
        String message = validateMessage(request.message());
        String prompt = buildPrompt(message, request.context());
        StreamOptions options = resolveOptions(request.voiceSettings());
        ChunkAccumulator accumulator = new ChunkAccumulator(options);
        long startedAt = System.currentTimeMillis();

        Flux<ServerSentEvent<Map<String, Object>>> textAndAudio = chatClient.prompt()
                .user(prompt)
                .stream()
                .content()
                .concatMap(delta -> {
                    accumulator.append(delta);
                    Flux<ServerSentEvent<Map<String, Object>>> textEvent = Flux.just(event("text", Map.of(
                            "content", delta,
                            "timestamp", Instant.now().toString()
                    )));
                    List<String> chunks = accumulator.drainReadyChunks();
                    if (chunks.isEmpty()) {
                        return textEvent;
                    }
                    return Flux.concat(textEvent, Flux.fromIterable(chunks)
                            .concatMap(chunk -> audioEvent(chunk, accumulator.nextChunkIndex(), options, startedAt)));
                });

        Mono<ServerSentEvent<Map<String, Object>>> start = Mono.just(event("start", Map.of(
                "voiceModel", options.model(),
                "voiceSettings", Map.of(
                        "chunkSize", options.chunkSize(),
                        "minChunkSize", options.minChunkSize(),
                        "maxChunkSize", options.maxChunkSize(),
                        "audioFormat", options.audioFormat().fileExtension(),
                        "naturalBreaks", options.naturalBreaks()
                ),
                "timestamp", Instant.now().toString()
        )));

        Flux<ServerSentEvent<Map<String, Object>>> tail = Flux.defer(() -> {
            List<String> finalChunks = accumulator.drainFinalChunks();
            Flux<ServerSentEvent<Map<String, Object>>> audio = Flux.fromIterable(finalChunks)
                    .concatMap(chunk -> audioEvent(chunk, accumulator.nextChunkIndex(), options, startedAt));
            Mono<ServerSentEvent<Map<String, Object>>> done = Mono.just(event("done", Map.of(
                    "totalChunks", accumulator.chunkCount(),
                    "totalStreamingTimeMs", System.currentTimeMillis() - startedAt,
                    "timestamp", Instant.now().toString()
            )));
            return Flux.concat(audio, done);
        });

        return Flux.concat(start, textAndAudio, tail)
                .onErrorResume(error -> {
                    log.warn("Voice stream failed: {}", error.getMessage());
                    return Flux.just(event("error", Map.of(
                            "error", "Streaming voice request failed",
                            "message", safeErrorMessage(error),
                            "timestamp", Instant.now().toString()
                    )));
                });
    }

    private Mono<ServerSentEvent<Map<String, Object>>> audioEvent(String originalText,
                                                                 int chunkIndex,
                                                                 StreamOptions options,
                                                                 long startedAt) {
        String cleanedText = speechService.cleanTextForSpeech(originalText);
        if (cleanedText.length() < 3) {
            return Mono.empty();
        }
        int estimatedDurationMs = estimateAudioDuration(cleanedText);
        return speechService.synthesize(cleanedText, options.model(), options.audioFormat())
                .map(audio -> event("audio", mapOf(
                        "chunkIndex", chunkIndex,
                        "audio", Base64.getEncoder().encodeToString(audio.bytes()),
                        "text", originalText,
                        "mimeType", audio.mediaType().toString(),
                        "audioFormat", options.audioFormat().fileExtension(),
                        "estimatedDuration", estimatedDurationMs,
                        "actualSize", audio.bytes().length,
                        "streamingLatency", System.currentTimeMillis() - startedAt,
                        "timestamp", Instant.now().toString(),
                        "wordCount", wordCount(originalText),
                        "cleanedWordCount", wordCount(cleanedText)
                )))
                .onErrorResume(error -> {
                    log.warn("TTS failed for voice chunk {}: {}", chunkIndex, error.getMessage());
                    return Mono.just(event("audio-error", mapOf(
                            "chunkIndex", chunkIndex,
                            "text", originalText,
                            "error", "Failed to generate audio chunk",
                            "timestamp", Instant.now().toString()
                    )));
                });
    }

    private String validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("'message' must not be empty");
        }
        String trimmed = message.trim();
        int max = props.getVoiceStream().getMaxMessageChars();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("'message' must be at most " + max + " characters");
        }
        return trimmed;
    }

    private String buildPrompt(String message, String context) {
        if (context == null || context.isBlank()) {
            return message;
        }
        return "Conversation context:\n" + context.trim() + "\n\nUser question:\n" + message;
    }

    private StreamOptions resolveOptions(VoiceSettings settings) {
        AiGatewayProperties.VoiceStream defaults = props.getVoiceStream();
        String requestedModel = settings != null ? settings.model() : null;
        String model = isBlank(requestedModel) ? props.getTts().getDeepgram().getModel() : requestedModel.trim();
        AudioFormat audioFormat = speechService.parseFormat(settings != null ? settings.audioFormat() : null);
        int chunkSize = bounded(settings != null ? settings.chunkSize() : null,
                defaults.getChunkSize(), 5, defaults.getMaxChunkSize());
        int minChunkSize = bounded(settings != null ? settings.minChunkSize() : null,
                defaults.getMinChunkSize(), 3, chunkSize);
        int maxChunkSize = bounded(settings != null ? settings.maxChunkSize() : null,
                defaults.getMaxChunkSize(), chunkSize, 120);
        boolean naturalBreaks = settings == null || settings.naturalBreaks() == null || settings.naturalBreaks();
        int maxAudioChunks = Math.max(1, defaults.getMaxAudioChunks());
        return new StreamOptions(model, audioFormat, chunkSize, minChunkSize, maxChunkSize, naturalBreaks, maxAudioChunks);
    }

    private int bounded(Integer value, int defaultValue, int min, int max) {
        int resolved = value != null ? value : defaultValue;
        if (resolved < min || resolved > max) {
            throw new IllegalArgumentException("Voice stream chunk settings are out of range");
        }
        return resolved;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int estimateAudioDuration(String text) {
        return Math.round((wordCount(text) / 2.5f) * 1000);
    }

    private int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private String safeErrorMessage(Throwable error) {
        return error instanceof IllegalArgumentException ? error.getMessage() : "Internal error";
    }

    private ServerSentEvent<Map<String, Object>> event(String name, Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder()
                .event(name)
                .data(data)
                .build();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }

    private record StreamOptions(String model,
                                 AudioFormat audioFormat,
                                 int chunkSize,
                                 int minChunkSize,
                                 int maxChunkSize,
                                 boolean naturalBreaks,
                                 int maxAudioChunks) {
    }

    private class ChunkAccumulator {
        private final StreamOptions options;
        private final StringBuilder buffer = new StringBuilder();
        private int chunkCount;

        private ChunkAccumulator(StreamOptions options) {
            this.options = options;
        }

        void append(String delta) {
            if (delta != null) {
                buffer.append(delta);
            }
        }

        List<String> drainReadyChunks() {
            List<String> chunks = new ArrayList<>();
            while (chunkCount + chunks.size() < options.maxAudioChunks()) {
                String chunk = nextReadyChunk(false);
                if (chunk == null) {
                    break;
                }
                chunks.add(chunk);
            }
            return chunks;
        }

        List<String> drainFinalChunks() {
            List<String> chunks = new ArrayList<>();
            while (chunkCount + chunks.size() < options.maxAudioChunks() && !buffer.toString().isBlank()) {
                String chunk = nextReadyChunk(true);
                if (chunk == null) {
                    break;
                }
                chunks.add(chunk);
            }
            return chunks;
        }

        int nextChunkIndex() {
            return chunkCount++;
        }

        int chunkCount() {
            return chunkCount;
        }

        private String nextReadyChunk(boolean finalDrain) {
            String text = buffer.toString().trim();
            if (text.isBlank()) {
                buffer.setLength(0);
                return null;
            }
            int words = wordCount(text);
            if (!finalDrain && words < options.minChunkSize()) {
                return null;
            }

            int breakIndex = options.naturalBreaks() ? naturalBreakPoint(text, options.chunkSize()) : -1;
            if (breakIndex <= 0 && (words >= options.chunkSize() || words >= options.maxChunkSize() || finalDrain)) {
                breakIndex = wordBoundaryIndex(text, Math.min(words, options.chunkSize()));
            }
            if (breakIndex <= 0) {
                return null;
            }

            String chunk = text.substring(0, breakIndex).trim();
            String remainder = text.substring(Math.min(breakIndex, text.length())).trim();
            buffer.setLength(0);
            buffer.append(remainder);
            return chunk;
        }

        private int naturalBreakPoint(String text, int targetWords) {
            int best = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':') {
                    int idx = i + 1;
                    int words = wordCount(text.substring(0, idx));
                    if (words >= Math.ceil(targetWords * 0.7) && words <= Math.ceil(targetWords * 1.3)) {
                        return idx;
                    }
                    if (words <= targetWords) {
                        best = idx;
                    }
                }
            }
            return best;
        }

        private int wordBoundaryIndex(String text, int targetWords) {
            int words = 0;
            boolean inWord = false;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isWhitespace(text.charAt(i))) {
                    if (inWord) {
                        inWord = false;
                        if (words >= targetWords) {
                            return i;
                        }
                    }
                } else if (!inWord) {
                    words++;
                    inWord = true;
                }
            }
            return text.length();
        }
    }
}
