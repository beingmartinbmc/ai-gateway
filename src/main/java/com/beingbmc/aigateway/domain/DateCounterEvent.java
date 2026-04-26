package com.beingbmc.aigateway.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "date_counter_events")
public class DateCounterEvent {

    @Id
    private String id;

    @Field("title")
    private String title;

    @Field("description")
    private String description;

    @Indexed(name = "idx_date_counter_events_event_date")
    @Field("eventDate")
    private String eventDate;

    @Indexed(name = "idx_date_counter_events_category")
    @Field("category")
    private String category;

    @Field("metadata")
    private Metadata metadata;

    @Indexed(name = "idx_date_counter_events_created_at")
    @Field("createdAt")
    private Instant createdAt;

    @Field("updatedAt")
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metadata {
        @Field("labels")
        private java.util.List<String> labels;

        @Field("reaction")
        private String reaction;

        @Field("comments")
        private String comments;
    }
}
