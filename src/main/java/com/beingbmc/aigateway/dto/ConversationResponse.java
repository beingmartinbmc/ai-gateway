package com.beingbmc.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ConversationResponse(@JsonProperty("_id") String id,
                                   String userInput,
                                   String aiResponse,
                                   Instant timestamp,
                                   List<String> books) {
}
