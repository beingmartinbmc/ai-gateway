package com.beingbmc.aigateway.dto;

import java.time.Instant;
import java.util.List;

/**
 * Completed conversation payload stored for the Epic UI.
 *
 * @param userInput user's original question or situation
 * @param aiResponse assistant response text to display
 * @param timestamp when the user asked; defaults to server time when omitted
 * @param book optional selected sacred-text key; {@code ALL} expands to every supported book
 * @param books optional selected sacred-text keys; {@code ALL} expands to every supported book
 */
public record ConversationCreateRequest(String userInput,
                                        String aiResponse,
                                        Instant timestamp,
                                        String book,
                                        List<String> books) {
}
