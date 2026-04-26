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
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "date_counter_comments")
public class DateCounterComment {

    @Id
    private String id;

    @Indexed(name = "idx_date_counter_comments_event_id")
    @Field("eventId")
    private String eventId;

    @Field("content")
    private String content;

    @Field("author")
    private String author;

    @Field("metadata")
    private Map<String, Object> metadata;

    @Indexed(name = "idx_date_counter_comments_created_at")
    @Field("createdAt")
    private Instant createdAt;

    @Field("updatedAt")
    private Instant updatedAt;
}
