package com.beingbmc.aigateway.dto;

/**
 * JSON request body for {@code POST /api/v1/chat}.
 *
 * @param message user question (required)
 * @param context optional prior-conversation / domain context
 */
public record ChatRequest(String message, String context) {}
