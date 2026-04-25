package com.beingbmc.aigateway.dto;

import java.time.Instant;

public record ChatResponseDto(
        String answer,
        boolean cached,
        Double similarity,
        Instant timestamp
) {
    public static ChatResponseDto fresh(String answer) {
        return new ChatResponseDto(answer, false, null, Instant.now());
    }

    public static ChatResponseDto fromCache(String answer, double similarity) {
        return new ChatResponseDto(answer, true, similarity, Instant.now());
    }
}
