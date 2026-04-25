package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.ChatRequest;
import com.beingbmc.aigateway.dto.ChatResponseDto;
import com.beingbmc.aigateway.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    /** JSON entry-point — plain text chat (no attachments). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponseDto> chatJson(@RequestBody Mono<ChatRequest> request) {
        return request.flatMap(req -> chatService.chat(req.message(), req.context(), null, null));
    }

    /** Multipart entry-point — supports optional file and/or image upload. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponseDto> chatMultipart(ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            String message = textPart(parts, "message");
            String context = textPart(parts, "context");
            FilePart file = filePart(parts, "file");
            FilePart image = filePart(parts, "image");
            return chatService.chat(message, context, file, image);
        });
    }

    private String textPart(MultiValueMap<String, Part> parts, String name) {
        Part p = parts.getFirst(name);
        if (p instanceof FormFieldPart f) {
            return f.value();
        }
        return null;
    }

    private FilePart filePart(MultiValueMap<String, Part> parts, String name) {
        Part p = parts.getFirst(name);
        return (p instanceof FilePart fp) ? fp : null;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleServerError(RuntimeException ex) {
        log.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal error"));
    }
}
