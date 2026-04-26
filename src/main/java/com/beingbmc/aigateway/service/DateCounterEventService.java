package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.domain.DateCounterEvent;
import com.beingbmc.aigateway.dto.DateCounterApiResponse;
import com.beingbmc.aigateway.dto.DateCounterEventRequest;
import com.beingbmc.aigateway.dto.DateCounterEventResponse;
import com.beingbmc.aigateway.dto.DateCounterListResponse;
import com.beingbmc.aigateway.repository.DateCounterEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DateCounterEventService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final DateCounterEventRepository eventRepository;
    private final ReactiveMongoTemplate mongoTemplate;

    public Mono<DateCounterListResponse<DateCounterEventResponse>> list(Integer limitParam,
                                                                        Integer skipParam,
                                                                        String category,
                                                                        String sortBy,
                                                                        String sortOrder) {
        int limit = normalizeLimit(limitParam);
        int skip = Math.max(0, skipParam == null ? 0 : skipParam);
        Query query = new Query();
        Query rowsQuery = new Query();
        if (!isBlank(category)) {
            query.addCriteria(Criteria.where("category").is(category.trim()));
            rowsQuery.addCriteria(Criteria.where("category").is(category.trim()));
        }

        rowsQuery.with(Sort.by(direction(sortOrder), eventSortField(sortBy)))
                .skip(skip)
                .limit(limit);

        Mono<List<DateCounterEventResponse>> rows = mongoTemplate.find(rowsQuery, DateCounterEvent.class)
                .map(this::toResponse)
                .collectList();
        Mono<Long> total = mongoTemplate.count(query, DateCounterEvent.class);

        return Mono.zip(rows, total)
                .map(tuple -> {
                    long count = tuple.getT2();
                    return new DateCounterListResponse<>(
                            true,
                            tuple.getT1(),
                            new DateCounterListResponse.Pagination(count, limit, skip, skip + limit < count));
                });
    }

    public Mono<DateCounterApiResponse<DateCounterEventResponse>> getById(String id) {
        requireId(id);
        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Event not found")))
                .map(event -> DateCounterApiResponse.ok(toResponse(event)));
    }

    public Mono<DateCounterApiResponse<DateCounterEventResponse>> create(DateCounterEventRequest request) {
        validateCreate(request);
        Instant now = Instant.now();
        DateCounterEvent event = DateCounterEvent.builder()
                .title(request.title().trim())
                .description(request.description())
                .eventDate(request.eventDate().trim())
                .category(isBlank(request.category()) ? "general" : request.category().trim())
                .metadata(toDomainMetadata(request.metadata()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return eventRepository.save(event)
                .map(saved -> DateCounterApiResponse.ok("Event created successfully", toResponse(saved)));
    }

    public Mono<DateCounterApiResponse<DateCounterEventResponse>> update(String id, DateCounterEventRequest request) {
        requireId(id);
        if (request == null) {
            return Mono.error(new IllegalArgumentException("Request body is required"));
        }
        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Event not found")))
                .map(event -> applyUpdates(event, request))
                .flatMap(eventRepository::save)
                .map(saved -> DateCounterApiResponse.ok("Event updated successfully", toResponse(saved)));
    }

    public Mono<DateCounterApiResponse<Void>> delete(String id) {
        requireId(id);
        return eventRepository.deleteById(id)
                .thenReturn(DateCounterApiResponse.<Void>ok("Event deleted successfully", null));
    }

    public Mono<DateCounterApiResponse<List<DateCounterEventResponse>>> upcoming(Integer limitParam) {
        int limit = normalizeLimit(limitParam);
        Query query = Query.query(Criteria.where("eventDate").gte(LocalDate.now().toString()))
                .with(Sort.by(Sort.Direction.ASC, "eventDate"))
                .limit(limit);
        return mongoTemplate.find(query, DateCounterEvent.class)
                .map(this::toResponse)
                .collectList()
                .map(DateCounterApiResponse::ok);
    }

    public Mono<DateCounterApiResponse<List<DateCounterEventResponse>>> range(String startDate,
                                                                             String endDate,
                                                                             Integer limitParam,
                                                                             Integer skipParam) {
        if (isBlank(startDate) || isBlank(endDate)) {
            return Mono.error(new IllegalArgumentException("startDate and endDate are required"));
        }
        int limit = normalizeLimit(limitParam);
        int skip = Math.max(0, skipParam == null ? 0 : skipParam);
        Query query = Query.query(Criteria.where("eventDate").gte(startDate.trim()).lte(endDate.trim()))
                .with(Sort.by(Sort.Direction.ASC, "eventDate"))
                .skip(skip)
                .limit(limit);
        return mongoTemplate.find(query, DateCounterEvent.class)
                .map(this::toResponse)
                .collectList()
                .map(DateCounterApiResponse::ok);
    }

    private DateCounterEvent applyUpdates(DateCounterEvent event, DateCounterEventRequest request) {
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            event.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            event.setDescription(request.description());
        }
        if (request.eventDate() != null) {
            if (request.eventDate().isBlank()) {
                throw new IllegalArgumentException("eventDate must not be blank");
            }
            event.setEventDate(request.eventDate().trim());
        }
        if (request.category() != null) {
            event.setCategory(isBlank(request.category()) ? "general" : request.category().trim());
        }
        if (request.metadata() != null) {
            event.setMetadata(mergeMetadata(event.getMetadata(), request.metadata()));
        }
        event.setUpdatedAt(Instant.now());
        return event;
    }

    private DateCounterEvent.Metadata mergeMetadata(DateCounterEvent.Metadata existing,
                                                   DateCounterEventRequest.Metadata updates) {
        DateCounterEvent.Metadata metadata = existing == null ? new DateCounterEvent.Metadata() : existing;
        if (updates.labels() != null) {
            metadata.setLabels(updates.labels());
        }
        if (updates.reaction() != null) {
            metadata.setReaction(updates.reaction());
        }
        if (updates.comments() != null) {
            metadata.setComments(updates.comments());
        }
        return metadata;
    }

    private DateCounterEvent.Metadata toDomainMetadata(DateCounterEventRequest.Metadata metadata) {
        if (metadata == null) {
            return DateCounterEvent.Metadata.builder().labels(List.of()).build();
        }
        return DateCounterEvent.Metadata.builder()
                .labels(metadata.labels() == null ? List.of() : metadata.labels())
                .reaction(metadata.reaction())
                .comments(metadata.comments())
                .build();
    }

    private DateCounterEventResponse toResponse(DateCounterEvent event) {
        DateCounterEvent.Metadata metadata = event.getMetadata();
        DateCounterEventResponse.Metadata responseMetadata = metadata == null
                ? new DateCounterEventResponse.Metadata(List.of(), null, null)
                : new DateCounterEventResponse.Metadata(
                metadata.getLabels() == null ? List.of() : metadata.getLabels(),
                metadata.getReaction(),
                metadata.getComments());
        return new DateCounterEventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getEventDate(),
                event.getCategory(),
                responseMetadata,
                event.getCreatedAt(),
                event.getUpdatedAt());
    }

    private void validateCreate(DateCounterEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.title())) {
            throw new IllegalArgumentException("title is required");
        }
        if (isBlank(request.eventDate())) {
            throw new IllegalArgumentException("eventDate is required");
        }
    }

    private void requireId(String id) {
        if (isBlank(id)) {
            throw new IllegalArgumentException("id is required");
        }
    }

    private int normalizeLimit(Integer limitParam) {
        return Math.min(MAX_LIMIT, Math.max(1, limitParam == null ? DEFAULT_LIMIT : limitParam));
    }

    private Sort.Direction direction(String sortOrder) {
        return "desc".equalsIgnoreCase(sortOrder) ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    private String eventSortField(String sortBy) {
        if (isBlank(sortBy)) {
            return "eventDate";
        }
        return switch (sortBy) {
            case "title", "eventDate", "category", "createdAt", "updatedAt" -> sortBy;
            default -> "eventDate";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
