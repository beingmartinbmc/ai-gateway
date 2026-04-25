package com.beingbmc.aigateway.dto;

/**
 * JSON request body for {@code POST /api/v1/voice/stream}.
 *
 * @param message user prompt
 * @param context optional prior-conversation or domain context
 * @param voiceSettings optional Deepgram/chunking overrides
 */
public record VoiceStreamRequest(String message, String context, VoiceSettings voiceSettings) {

    public record VoiceSettings(String model,
                                String audioFormat,
                                Integer chunkSize,
                                Integer minChunkSize,
                                Integer maxChunkSize,
                                Boolean naturalBreaks) {
    }
}
