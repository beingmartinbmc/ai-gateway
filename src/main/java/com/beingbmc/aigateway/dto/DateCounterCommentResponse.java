package com.beingbmc.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public record DateCounterCommentResponse(@JsonProperty("_id") String id,
                                         String eventId,
                                         String content,
                                         String author,
                                         Map<String, Object> metadata,
                                         Instant createdAt,
                                         Instant updatedAt) {
}
