package com.beingbmc.aigateway.dto;

/**
 * JSON request body for {@code POST /api/v1/tts}.
 *
 * @param text text to synthesize
 * @param model optional Deepgram voice model override
 * @param audioFormat optional audio format: {@code mp3} or {@code wav}
 */
public record TtsRequest(String text, String model, String audioFormat) {
}
