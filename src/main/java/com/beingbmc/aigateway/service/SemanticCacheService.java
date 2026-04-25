package com.beingbmc.aigateway.service;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Semantic cache backed by an in-memory {@link VectorStore}.
 * <p>
 * On each call we embed the incoming query, do a top-1 similarity search,
 * and return the cached answer if similarity >= configured threshold.
 * <p>
 * Eviction strategy:
 *   1. <b>TTL</b> (lazy): on lookup, if the matched entry is older than
 *      {@code ai-gateway.cache.ttl-seconds}, it is removed and treated as a miss.
 *   2. <b>Size cap</b>: when more than {@code maxEntries} live, the oldest
 *      entry (FIFO insertion order) is evicted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final String META_ANSWER = "answer";
    private static final String META_QUERY = "query";

    private final VectorStore vectorStore;
    private final AiGatewayProperties props;
    private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();
    private final Map<String, Instant> insertedAt = new ConcurrentHashMap<>();

    public Optional<Hit> lookup(String query) {
        if (!props.getCache().isEnabled() || query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(1)
                    .similarityThreshold(props.getCache().getSimilarityThreshold())
                    .build();
            List<Document> results = vectorStore.similaritySearch(req);
            if (results.isEmpty()) {
                return Optional.empty();
            }
            Document doc = results.getFirst();
            if (isExpired(doc.getId())) {
                evict(doc.getId());
                log.debug("Semantic cache entry expired (id={}) \u2014 treated as miss", doc.getId());
                return Optional.empty();
            }
            Object answer = doc.getMetadata().get(META_ANSWER);
            Double score = doc.getScore();
            if (answer == null) {
                return Optional.empty();
            }
            log.debug("Semantic cache hit (score={})", score);
            return Optional.of(new Hit(answer.toString(), score == null ? 0.0 : score));
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void store(String query, String answer) {
        if (!props.getCache().isEnabled() || query == null || query.isBlank() || answer == null) {
            return;
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_QUERY, query);
            metadata.put(META_ANSWER, answer);
            Document doc = new Document(query, metadata);
            vectorStore.add(List.of(doc));
            insertionOrder.addLast(doc.getId());
            insertedAt.put(doc.getId(), Instant.now());
            evictIfNeeded();
        } catch (Exception e) {
            log.warn("Semantic cache store failed: {}", e.getMessage());
        }
    }

    private boolean isExpired(String docId) {
        long ttlSec = props.getCache().getTtlSeconds();
        if (ttlSec <= 0) {
            return false;
        }
        Instant inserted = insertedAt.get(docId);
        if (inserted == null) {
            // Unknown entry (e.g. survived a restart) — treat as expired so we don't return stale data.
            return true;
        }
        return Instant.now().isAfter(inserted.plusSeconds(ttlSec));
    }

    private void evict(String docId) {
        try {
            vectorStore.delete(List.of(docId));
        } catch (Exception e) {
            log.debug("Cache eviction failed for {}: {}", docId, e.getMessage());
        }
        insertionOrder.remove(docId);
        insertedAt.remove(docId);
    }

    private void evictIfNeeded() {
        int max = props.getCache().getMaxEntries();
        while (insertionOrder.size() > max) {
            String oldId = insertionOrder.pollFirst();
            if (oldId != null) {
                evict(oldId);
            }
        }
    }

    public record Hit(String answer, double similarity) {}
}
