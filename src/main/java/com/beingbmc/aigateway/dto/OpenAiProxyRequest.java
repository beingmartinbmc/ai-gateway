package com.beingbmc.aigateway.dto;

import java.util.List;

/**
 * OpenAI-compatible request body for longer prompt workflows.
 *
 * @param messages chat messages to forward
 * @param model optional model override
 * @param maxTokens optional output token cap
 * @param temperature optional sampling temperature
 * @param topP optional nucleus sampling value
 * @param frequencyPenalty optional frequency penalty
 * @param presencePenalty optional presence penalty
 */
public record OpenAiProxyRequest(List<Message> messages,
                                 String model,
                                 Integer maxTokens,
                                 Double temperature,
                                 Double topP,
                                 Double frequencyPenalty,
                                 Double presencePenalty) {

    public record Message(String role, String content) {
    }
}
