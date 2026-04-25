package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import com.beingbmc.aigateway.domain.LlmRequestInfo;
import com.beingbmc.aigateway.dto.ChatResponseDto;
import com.beingbmc.aigateway.repository.LlmRequestInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reactive orchestration of a chat request:
 *   1. semantic-cache lookup,
 *   2. assemble user prompt (message + context + optional text-file body),
 *   3. attach optional image as multimodal media,
 *   4. invoke the OpenAI chat client (blocking SDK, dispatched on boundedElastic),
 *   5. cache result for future semantic hits,
 *   6. persist an audit row to Mongo (TTL 90d).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_TEXT_FILE_CHARS = 20_000;

    private final ChatClient chatClient;
    private final SemanticCacheService cache;
    private final AiGatewayProperties props;
    private final LlmRequestInfoRepository requestRepo;

    public Mono<ChatResponseDto> chat(String message,
                                      String context,
                                      FilePart file,
                                      FilePart image) {
        if (message == null || message.isBlank()) {
            return Mono.error(new IllegalArgumentException("'message' must not be empty"));
        }

        boolean hasAttachment = file != null || image != null;
        String cacheKey = buildCacheKey(message, context);
        boolean skipCache = hasAttachment && props.getCache().isSkipWhenAttachment();

        Mono<Optional<SemanticCacheService.Hit>> cacheLookup = skipCache
                ? Mono.just(Optional.empty())
                : Mono.fromCallable(() -> cache.lookup(cacheKey))
                    .subscribeOn(Schedulers.boundedElastic());

        return cacheLookup.flatMap(hit -> {
            if (hit.isPresent()) {
                return Mono.just(ChatResponseDto.fromCache(hit.get().answer(), hit.get().similarity()));
            }
            return invokeModel(message, context, file, image)
                    .flatMap(invocation -> {
                        Mono<Void> doCache = skipCache
                                ? Mono.empty()
                                : Mono.fromRunnable(() -> cache.store(cacheKey, invocation.answer()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then();
                        Mono<Void> doPersist = persistAudit(message, context, invocation)
                                .onErrorResume(e -> {
                                    log.warn("Failed to persist llm_request_info: {}", e.getMessage());
                                    return Mono.empty();
                                })
                                .then();
                        return Mono.when(doCache, doPersist)
                                .thenReturn(ChatResponseDto.fresh(invocation.answer()));
                    });
        });
    }

    /* ---------- model invocation (reactive wrapper around the blocking SDK) ---------- */

    private Mono<Invocation> invokeModel(String message,
                                         String context,
                                         FilePart file,
                                         FilePart image) {
        Mono<String> filePromptPart = (file == null) ? Mono.just("") : readTextFile(file);
        Mono<ImagePayload> imagePayload = (image == null)
                ? Mono.just(ImagePayload.EMPTY)
                : readBytes(image).map(b -> new ImagePayload(b, mimeTypeOf(image)));

        return Mono.zip(filePromptPart, imagePayload)
                .flatMap(t -> {
                    String prompt = buildPrompt(message, context, file, t.getT1());
                    ImagePayload img = t.getT2();
                    return Mono.fromCallable(() -> callLlm(prompt, img))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(resp -> new Invocation(
                                    prompt,
                                    extractAnswer(resp),
                                    extractModel(resp)));
                })
                .onErrorMap(e -> !(e instanceof IllegalArgumentException),
                        e -> {
                            log.error("LLM call failed: {}", e.getMessage(), e);
                            return new RuntimeException("Upstream model error", e);
                        });
    }

    private ChatResponse callLlm(String prompt, ImagePayload img) {
        return chatClient.prompt()
                .user(u -> {
                    u.text(prompt);
                    if (img.hasData()) {
                        Media media = Media.builder()
                                .mimeType(img.mime())
                                .data(new ByteArrayResource(img.bytes()))
                                .build();
                        u.media(media);
                    }
                })
                .call()
                .chatResponse();
    }

    private String extractAnswer(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return "";
        }
        return Optional.ofNullable(resp.getResult().getOutput().getText()).orElse("");
    }

    private String extractModel(ChatResponse resp) {
        try {
            return resp != null && resp.getMetadata() != null ? resp.getMetadata().getModel() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- prompt assembly ---------- */

    private String buildPrompt(String message, String context, FilePart file, String fileBody) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isBlank()) {
            sb.append("Conversation context:\n").append(context.trim()).append("\n\n");
        }
        sb.append("User question:\n").append(message.trim());
        if (file != null) {
            if (fileBody != null && !fileBody.isEmpty()) {
                sb.append("\n\nAttached file (").append(file.filename()).append("):\n```\n")
                        .append(fileBody).append("\n```");
            } else {
                sb.append("\n\n[Attached non-text file: ").append(file.filename()).append(" — ignored]");
            }
        }
        return sb.toString();
    }

    private String buildCacheKey(String message, String context) {
        if (context == null || context.isBlank()) {
            return message.trim();
        }
        return context.trim() + "\n---\n" + message.trim();
    }

    /* ---------- FilePart helpers (non-blocking) ---------- */

    private Mono<String> readTextFile(FilePart file) {
        if (!looksTextual(file)) {
            return Mono.just("");
        }
        return readBytes(file).map(bytes -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() > MAX_TEXT_FILE_CHARS) {
                text = text.substring(0, MAX_TEXT_FILE_CHARS) + "\n... [truncated]";
            }
            return text;
        });
    }

    private Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                });
    }

    private boolean looksTextual(FilePart file) {
        MediaType ct = file.headers().getContentType();
        if (ct != null) {
            String s = ct.toString();
            if (s.startsWith("text/")
                    || s.contains("json") || s.contains("xml") || s.contains("yaml")
                    || s.contains("javascript") || s.contains("csv")) {
                return true;
            }
        }
        String name = Optional.ofNullable(file.filename()).orElse("").toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")
                || name.endsWith(".json") || name.endsWith(".csv")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".xml");
    }

    private MimeType mimeTypeOf(FilePart image) {
        MediaType ct = image.headers().getContentType();
        return ct != null ? MimeTypeUtils.parseMimeType(ct.toString()) : MimeTypeUtils.IMAGE_PNG;
    }

    /* ---------- audit log ---------- */

    private Mono<LlmRequestInfo> persistAudit(String message, String context, Invocation inv) {
        String body = "{\"message\":" + jsonEscape(message)
                + ",\"context\":" + jsonEscape(context) + "}";
        LlmRequestInfo doc = LlmRequestInfo.builder()
                .uuid(UUID.randomUUID().toString())
                .body(body)
                .model(inv.model())
                .prompt(inv.prompt())
                .response(inv.answer())
                .createdAt(Instant.now())
                .build();
        return requestRepo.save(doc);
    }

    private String jsonEscape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /* ---------- value carriers ---------- */

    private record Invocation(String prompt, String answer, String model) {}

    private record ImagePayload(byte[] bytes, MimeType mime) {
        static final ImagePayload EMPTY = new ImagePayload(new byte[0], null);
        boolean hasData() { return bytes != null && bytes.length > 0; }
    }
}
