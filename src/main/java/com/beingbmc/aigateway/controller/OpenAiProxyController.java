package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.OpenAiProxyRequest;
import com.beingbmc.aigateway.service.OpenAiProxyService;
import com.beingbmc.aigateway.service.OpenAiProxyService.OpenAiProxyConfigurationException;
import com.beingbmc.aigateway.service.OpenAiProxyService.OpenAiProxyUpstreamException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/openai-proxy")
public class OpenAiProxyController {

    private final OpenAiProxyService openAiProxyService;
    private final ObjectMapper objectMapper;

    @Operation(
            summary = "OpenAI-compatible proxy for long prompt workflows",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Raw OpenAI chat completion response"),
                    @ApiResponse(responseCode = "400", description = "Invalid messages payload"),
                    @ApiResponse(responseCode = "502", description = "OpenAI upstream error")
            })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> complete(@RequestBody Mono<OpenAiProxyRequest> request) {
        return request
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body is required")))
                .flatMap(openAiProxyService::complete)
                .map(this::ok)
                .onErrorResume(this::errorResponse);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> completeMultipart(ServerWebExchange exchange) {
        return exchange.getMultipartData()
                .flatMap(parts -> openAiProxyService.completeWithImages(requestFromParts(parts), imageParts(parts)))
                .map(this::ok)
                .onErrorResume(this::errorResponse);
    }

    private OpenAiProxyRequest requestFromParts(MultiValueMap<String, Part> parts) {
        String requestJson = textPart(parts, "request");
        if (!isBlank(requestJson)) {
            return parseRequest(requestJson);
        }

        String messagesJson = textPart(parts, "messages");
        if (!isBlank(messagesJson)) {
            String trimmed = messagesJson.trim();
            if (trimmed.startsWith("{")) {
                return parseRequest(trimmed);
            }
            return new OpenAiProxyRequest(
                    parseMessages(trimmed),
                    textPart(parts, "model"),
                    intPart(parts, "maxTokens"),
                    doublePart(parts, "temperature"),
                    doublePart(parts, "topP"),
                    doublePart(parts, "frequencyPenalty"),
                    doublePart(parts, "presencePenalty"));
        }

        String message = textPart(parts, "message");
        if (!isBlank(message)) {
            return new OpenAiProxyRequest(
                    List.of(new OpenAiProxyRequest.Message("user", message)),
                    textPart(parts, "model"),
                    intPart(parts, "maxTokens"),
                    doublePart(parts, "temperature"),
                    doublePart(parts, "topP"),
                    doublePart(parts, "frequencyPenalty"),
                    doublePart(parts, "presencePenalty"));
        }

        throw new IllegalArgumentException("Multipart request must include request, messages, or message field");
    }

    private OpenAiProxyRequest parseRequest(String json) {
        try {
            return objectMapper.readValue(json, OpenAiProxyRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request JSON");
        }
    }

    private List<OpenAiProxyRequest.Message> parseMessages(String json) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, OpenAiProxyRequest.Message.class);
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid messages JSON");
        }
    }

    private List<FilePart> imageParts(MultiValueMap<String, Part> parts) {
        List<FilePart> images = new ArrayList<>();
        parts.forEach((name, values) -> values.forEach(part -> {
            if (part instanceof FilePart file && shouldTreatAsImage(name, file)) {
                images.add(file);
            }
        }));
        return images;
    }

    private boolean shouldTreatAsImage(String fieldName, FilePart file) {
        MediaType contentType = file.headers().getContentType();
        return "image".equals(fieldName)
                || "images".equals(fieldName)
                || (contentType != null && "image".equalsIgnoreCase(contentType.getType()));
    }

    private String textPart(MultiValueMap<String, Part> parts, String name) {
        Part p = parts.getFirst(name);
        if (p instanceof FormFieldPart f) {
            return f.value();
        }
        return null;
    }

    private Integer intPart(MultiValueMap<String, Part> parts, String name) {
        String value = textPart(parts, name);
        return isBlank(value) ? null : Integer.valueOf(value);
    }

    private Double doublePart(MultiValueMap<String, Part> parts, String name) {
        String value = textPart(parts, name);
        return isBlank(value) ? null : Double.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseEntity<Object> ok(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private Mono<ResponseEntity<Object>> errorResponse(Throwable error) {
        HttpStatus status;
        String message;
        if (error instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            message = error.getMessage();
        } else if (error instanceof OpenAiProxyConfigurationException) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "OpenAI API is not configured";
        } else if (error instanceof OpenAiProxyUpstreamException || error instanceof TimeoutException) {
            status = HttpStatus.BAD_GATEWAY;
            message = "OpenAI request failed";
        } else {
            log.error("Unhandled OpenAI proxy error: {}", error.getMessage(), error);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Internal error";
        }

        return Mono.just(ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message)));
    }
}
