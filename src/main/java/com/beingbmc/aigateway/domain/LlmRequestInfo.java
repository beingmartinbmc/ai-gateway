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

/**
 * Audit trail of every LLM request the gateway has served.
 * <p>
 * Stored in the {@code llm_request_info} collection with a TTL of 90 days
 * (≈ 3 months) on the {@code created_at} field — Mongo automatically deletes
 * documents older than that.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "llm_request_info")
public class LlmRequestInfo {

    @Id
    @Field("uuid")
    private String uuid;

    /** Original request body (JSON-serialised). */
    @Field("body")
    private String body;

    /** Resolved chat-completion model (e.g. {@code gpt-4o-mini}). */
    @Field("model")
    private String model;

    /** The full assembled user prompt that was sent to the model. */
    @Field("prompt")
    private String prompt;

    /** Model output. */
    @Field("response")
    private String response;

    /** TTL anchor — Mongo deletes the document 90 days after this timestamp. */
    @Indexed(name = "ttl_created_at", expireAfter = "P90D")
    @Field("created_at")
    private Instant createdAt;
}
