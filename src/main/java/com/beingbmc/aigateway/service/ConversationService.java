package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.domain.Conversation;
import com.beingbmc.aigateway.dto.ConversationCreateRequest;
import com.beingbmc.aigateway.dto.ConversationListResponse;
import com.beingbmc.aigateway.dto.ConversationResponse;
import com.beingbmc.aigateway.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final List<String> ALL_BOOKS = List.of(
            "BHAGAVAD_GITA",
            "VEDAS",
            "QURAN",
            "BIBLE",
            "GURU_GRANTH_SAHIB",
            "TRIPITAKA",
            "TAO_TE_CHING",
            "ANALECTS_OF_CONFUCIUS",
            "DHAMMAPADA",
            "UPANISHADS",
            "TALMUD",
            "AVESTA");

    private final ConversationRepository conversationRepository;
    private final ReactiveMongoTemplate mongoTemplate;

    public Mono<ConversationResponse> create(ConversationCreateRequest request) {
        validate(request);
        Conversation conversation = Conversation.builder()
                .userInput(request.userInput().trim())
                .aiResponse(request.aiResponse().trim())
                .timestamp(request.timestamp() == null ? Instant.now() : request.timestamp())
                .books(normalizeBooks(request.book(), request.books()))
                .build();
        return conversationRepository.save(conversation).map(this::toResponse);
    }

    public Mono<ConversationListResponse> list(Integer pageParam,
                                               Integer limitParam,
                                               String orderParam,
                                               String book,
                                               List<String> books,
                                               Instant from,
                                               Instant to) {
        int page = Math.max(1, pageParam == null ? 1 : pageParam);
        int limit = Math.min(MAX_LIMIT, Math.max(1, limitParam == null ? DEFAULT_LIMIT : limitParam));
        long skip = (long) (page - 1) * limit;
        Sort.Direction direction = "asc".equalsIgnoreCase(orderParam) ? Sort.Direction.ASC : Sort.Direction.DESC;
        List<String> bookFilter = normalizeBooks(book, books);

        Query rowsQuery = baseQuery(bookFilter, from, to)
                .with(Sort.by(direction, "timestamp"))
                .skip(skip)
                .limit(limit);

        Mono<List<ConversationResponse>> conversations = mongoTemplate.find(rowsQuery, Conversation.class)
                .map(this::toResponse)
                .collectList();
        Mono<Long> totalCount = mongoTemplate.count(baseQuery(bookFilter, from, to), Conversation.class);

        return Mono.zip(conversations, totalCount)
                .map(tuple -> listResponse(tuple.getT1(), tuple.getT2(), page, limit, skip));
    }

    private ConversationListResponse listResponse(List<ConversationResponse> conversations,
                                                  long totalCount,
                                                  int page,
                                                  int limit,
                                                  long skip) {
        int totalPages = (int) Math.ceil((double) totalCount / limit);
        boolean hasNextPage = page < totalPages;
        boolean hasPrevPage = page > 1;
        return new ConversationListResponse(
                conversations,
                new ConversationListResponse.Pagination(
                        page,
                        totalPages,
                        totalCount,
                        limit,
                        skip,
                        hasNextPage,
                        hasPrevPage,
                        hasNextPage ? page + 1 : null,
                        hasPrevPage ? page - 1 : null));
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getUserInput(),
                conversation.getAiResponse(),
                conversation.getTimestamp(),
                conversation.getBooks() == null ? List.of() : conversation.getBooks());
    }

    private void validate(ConversationCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.userInput())) {
            throw new IllegalArgumentException("userInput is required");
        }
        if (isBlank(request.aiResponse())) {
            throw new IllegalArgumentException("aiResponse is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Query baseQuery(List<String> bookFilter, Instant from, Instant to) {
        Query query = new Query();
        if (!bookFilter.isEmpty()) {
            query.addCriteria(Criteria.where("books").in(bookFilter));
        }
        if (from != null || to != null) {
            Criteria timestamp = Criteria.where("timestamp");
            if (from != null) {
                timestamp = timestamp.gte(from);
            }
            if (to != null) {
                timestamp = timestamp.lte(to);
            }
            query.addCriteria(timestamp);
        }
        return query;
    }

    private List<String> normalizeBooks(String book, List<String> books) {
        Set<String> normalized = new LinkedHashSet<>();
        addBook(normalized, book);
        if (books != null) {
            books.forEach(value -> addBook(normalized, value));
        }
        return new ArrayList<>(normalized);
    }

    private void addBook(Set<String> normalized, String value) {
        String book = normalizeBook(value);
        if (book == null) {
            return;
        }
        if ("ALL".equals(book)) {
            normalized.addAll(ALL_BOOKS);
            return;
        }
        normalized.add(book);
    }

    private String normalizeBook(String value) {
        if (isBlank(value)) {
            return null;
        }
        String key = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return switch (key) {
            case "BHAGWADGITA", "BHAGWAD_GITA", "BHAGAVADGITA", "BHAGAVAD_GITA", "GITA" -> "BHAGAVAD_GITA";
            case "QURAN", "HOLY_QURAN" -> "QURAN";
            case "GURU_GRANTH", "GURU_GRANTH_SAHIB", "GRANTH_SAHIB" -> "GURU_GRANTH_SAHIB";
            case "TAO", "TAO_TE_CHING", "DAO_DE_JING" -> "TAO_TE_CHING";
            case "ANALECTS", "ANALECTS_OF_CONFUCIUS", "CONFUCIUS" -> "ANALECTS_OF_CONFUCIUS";
            case "ALL", "ALL_SACRED_TEXTS" -> "ALL";
            default -> key;
        };
    }
}
