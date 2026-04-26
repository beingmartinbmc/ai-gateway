package com.beingbmc.aigateway.controller;

import com.beingbmc.aigateway.dto.DateCounterCommentRequest;
import com.beingbmc.aigateway.service.DateCounterCommentService;
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

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/comments")
public class DateCounterCommentController {

    private final DateCounterCommentService commentService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> get(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String eventId,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(required = false) Integer skip,
                                            @RequestParam(required = false) String sortBy,
                                            @RequestParam(required = false) String sortOrder) {
        if (!normalizeAction(action).isEmpty() && !"by-event".equals(normalizeAction(action))) {
            return errorResponse(new IllegalArgumentException("Unsupported comments action"));
        }
        return ok(commentService.listByEvent(eventId, limit, skip, sortBy, sortOrder))
                .onErrorResume(this::errorResponse);
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> post(@RequestParam(required = false, defaultValue = "create") String action,
                                             @RequestParam(required = false) String id,
                                             @RequestBody(required = false) Mono<DateCounterCommentRequest> request) {
        Mono<?> result;
        if ("delete".equals(normalizeAction(action))) {
            result = commentService.delete(id);
        } else {
            result = body(request).flatMap(commentService::create);
        }
        return ok(result).onErrorResume(this::errorResponse);
    }

    private Mono<DateCounterCommentRequest> body(Mono<DateCounterCommentRequest> request) {
        return (request == null ? Mono.<DateCounterCommentRequest>empty() : request)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body is required")));
    }

    private Mono<ResponseEntity<Object>> ok(Mono<?> result) {
        return result.map(body -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body((Object) body));
    }

    private String normalizeAction(String action) {
        return action == null ? "" : action.trim().toLowerCase();
    }

    private Mono<ResponseEntity<Object>> errorResponse(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", error.getMessage())));
        }

        log.error("Unhandled date-counter comments API error: {}", error.getMessage(), error);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "Internal error")));
    }
}
