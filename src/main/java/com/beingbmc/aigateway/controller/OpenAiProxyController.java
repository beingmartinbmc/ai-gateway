package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.OpenAiProxyRequest;
import com.beingbmc.aigateway.service.OpenAiProxyService;
import com.beingbmc.aigateway.service.OpenAiProxyService.OpenAiProxyConfigurationException;
import com.beingbmc.aigateway.service.OpenAiProxyService.OpenAiProxyUpstreamException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/openai-proxy")
public class OpenAiProxyController {

    private final OpenAiProxyService openAiProxyService;

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
