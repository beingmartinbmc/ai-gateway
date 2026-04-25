package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.VoiceStreamRequest;
import com.beingbmc.aigateway.service.VoiceStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/voice")
public class VoiceStreamController {

    private final VoiceStreamService voiceStreamService;

    @Operation(
            summary = "Stream chat text and synthesized speech chunks",
            responses = {
                    @ApiResponse(responseCode = "200", description = "SSE stream with text/audio events"),
                    @ApiResponse(responseCode = "400", description = "Invalid request")
            })
    @PostMapping(path = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestBody Mono<VoiceStreamRequest> request) {
        return request
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body is required")))
                .flatMapMany(voiceStreamService::stream)
                .onErrorResume(error -> {
                    log.warn("Voice stream request rejected: {}", error.getMessage());
                    return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                            .event("error")
                            .data(Map.of(
                                    "error", "Streaming voice request failed",
                                    "message", error instanceof IllegalArgumentException
                                            ? error.getMessage()
                                            : "Internal error",
                                    "timestamp", Instant.now().toString()
                            ))
                            .build());
                });
    }
}
