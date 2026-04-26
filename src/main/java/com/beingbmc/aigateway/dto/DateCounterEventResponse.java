package com.beingbmc.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record DateCounterEventResponse(@JsonProperty("_id") String id,
                                       String title,
                                       String description,
                                       String eventDate,
                                       String category,
                                       Metadata metadata,
                                       Instant createdAt,
                                       Instant updatedAt) {

    public record Metadata(List<String> labels,
                           String reaction,
                           String comments) {
    }
}
