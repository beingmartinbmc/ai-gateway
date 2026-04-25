package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.TtsRequest;
import com.beingbmc.aigateway.service.DeepgramSpeechService;
import com.beingbmc.aigateway.service.DeepgramSpeechService.SpeechConfigurationException;
import com.beingbmc.aigateway.service.DeepgramSpeechService.UpstreamSpeechException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tts")
public class TtsController {

    private final DeepgramSpeechService speechService;

    @Operation(
            summary = "Generate speech audio from text",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Audio bytes",
                            content = @Content(mediaType = "audio/mpeg")),
                    @ApiResponse(responseCode = "400", description = "Invalid request"),
                    @ApiResponse(responseCode = "502", description = "Speech provider error")
            })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {"audio/mpeg", "audio/wav", MediaType.APPLICATION_JSON_VALUE})
    public Mono<ResponseEntity<Object>> synthesize(@RequestBody Mono<TtsRequest> request) {
        return request
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body is required")))
                .flatMap(speechService::synthesize)
                .flatMap(audio -> {
                    ResponseEntity<Object> response = ResponseEntity.ok()
                            .contentType(audio.mediaType())
                            .contentLength(audio.bytes().length)
                            .cacheControl(CacheControl.noCache())
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"speech." + audio.fileExtension() + "\"")
                            .body(audio.bytes());
                    return Mono.just(response);
                })
                .onErrorResume(this::errorResponse);
    }

    private Mono<ResponseEntity<Object>> errorResponse(Throwable error) {
        HttpStatus status;
        String message;

        if (error instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            message = error.getMessage();
        } else if (error instanceof SpeechConfigurationException) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Voice API is not configured";
        } else if (error instanceof UpstreamSpeechException
                || error instanceof TimeoutException
                || error instanceof WebClientRequestException) {
            status = HttpStatus.BAD_GATEWAY;
            message = "Failed to generate speech";
        } else {
            log.error("Unhandled TTS error: {}", error.getMessage(), error);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Internal error";
        }

        ResponseEntity<Object> response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
        return Mono.just(response);
    }
}
