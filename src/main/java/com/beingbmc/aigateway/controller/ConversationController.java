package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.ConversationCreateRequest;
import com.beingbmc.aigateway.dto.ConversationListResponse;
import com.beingbmc.aigateway.dto.ConversationResponse;
import com.beingbmc.aigateway.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/v1/conversations", "/api/conversations"})
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> create(@RequestBody Mono<ConversationCreateRequest> request) {
        return request
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body is required")))
                .flatMap(conversationService::create)
                .map(this::created)
                .onErrorResume(this::errorResponse);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConversationListResponse> list(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String order,
                                               @RequestParam(required = false) String book,
                                               @RequestParam(required = false) List<String> books,
                                               @RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to) {
        return conversationService.list(page, limit, order, book, books, from, to);
    }

    private ResponseEntity<Object> created(ConversationResponse conversation) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    private Mono<ResponseEntity<Object>> errorResponse(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", error.getMessage())));
        }

        log.error("Unhandled conversation API error: {}", error.getMessage(), error);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "Internal error")));
    }
}
