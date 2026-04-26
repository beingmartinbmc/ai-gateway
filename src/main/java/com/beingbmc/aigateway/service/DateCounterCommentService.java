package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.domain.DateCounterComment;
import com.beingbmc.aigateway.dto.DateCounterApiResponse;
import com.beingbmc.aigateway.dto.DateCounterCommentRequest;
import com.beingbmc.aigateway.dto.DateCounterCommentResponse;
import com.beingbmc.aigateway.dto.DateCounterListResponse;
import com.beingbmc.aigateway.repository.DateCounterCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DateCounterCommentService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DateCounterCommentRepository commentRepository;
    private final ReactiveMongoTemplate mongoTemplate;

    public Mono<DateCounterListResponse<DateCounterCommentResponse>> listByEvent(String eventId,
                                                                                 Integer limitParam,
                                                                                 Integer skipParam,
                                                                                 String sortBy,
                                                                                 String sortOrder) {
        requireEventId(eventId);
        int limit = normalizeLimit(limitParam);
        int skip = Math.max(0, skipParam == null ? 0 : skipParam);
        Query query = Query.query(Criteria.where("eventId").is(eventId.trim()));
        Query rowsQuery = Query.query(Criteria.where("eventId").is(eventId.trim()))
                .with(Sort.by(direction(sortOrder), commentSortField(sortBy)))
                .skip(skip)
                .limit(limit);

        Mono<List<DateCounterCommentResponse>> rows = mongoTemplate.find(rowsQuery, DateCounterComment.class)
                .map(this::toResponse)
                .collectList();
        Mono<Long> total = mongoTemplate.count(query, DateCounterComment.class);

        return Mono.zip(rows, total)
                .map(tuple -> {
                    long count = tuple.getT2();
                    return new DateCounterListResponse<>(
                            true,
                            tuple.getT1(),
                            new DateCounterListResponse.Pagination(count, limit, skip, skip + limit < count));
                });
    }

    public Mono<DateCounterApiResponse<DateCounterCommentResponse>> create(DateCounterCommentRequest request) {
        validateCreate(request);
        Instant now = Instant.now();
        DateCounterComment comment = DateCounterComment.builder()
                .eventId(request.eventId().trim())
                .content(request.content().trim())
                .author(isBlank(request.author()) ? "Anonymous" : request.author().trim())
                .metadata(request.metadata())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return commentRepository.save(comment)
                .map(saved -> DateCounterApiResponse.ok("Comment created successfully", toResponse(saved)));
    }

    public Mono<DateCounterApiResponse<Void>> delete(String id) {
        if (isBlank(id)) {
            return Mono.error(new IllegalArgumentException("id is required"));
        }
        return commentRepository.deleteById(id)
                .thenReturn(DateCounterApiResponse.<Void>ok("Comment deleted successfully", null));
    }

    private DateCounterCommentResponse toResponse(DateCounterComment comment) {
        return new DateCounterCommentResponse(
                comment.getId(),
                comment.getEventId(),
                comment.getContent(),
                comment.getAuthor(),
                comment.getMetadata(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }

    private void validateCreate(DateCounterCommentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        requireEventId(request.eventId());
        if (isBlank(request.content())) {
            throw new IllegalArgumentException("content is required");
        }
    }

    private void requireEventId(String eventId) {
        if (isBlank(eventId)) {
            throw new IllegalArgumentException("eventId is required");
        }
    }

    private int normalizeLimit(Integer limitParam) {
        return Math.min(MAX_LIMIT, Math.max(1, limitParam == null ? DEFAULT_LIMIT : limitParam));
    }

    private Sort.Direction direction(String sortOrder) {
        return "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
    }

    private String commentSortField(String sortBy) {
        if (isBlank(sortBy)) {
            return "createdAt";
        }
        return switch (sortBy) {
            case "author", "createdAt", "updatedAt" -> sortBy;
            default -> "createdAt";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
