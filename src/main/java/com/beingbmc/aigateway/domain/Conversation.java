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
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "conversations")
public class Conversation {

    @Id
    private String id;

    @Field("userInput")
    private String userInput;

    @Field("aiResponse")
    private String aiResponse;

    @Indexed(name = "idx_conversations_timestamp")
    @Field("timestamp")
    private Instant timestamp;

    @Indexed(name = "idx_conversations_books")
    @Field("books")
    private List<String> books;
}
