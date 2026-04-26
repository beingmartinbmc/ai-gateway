package com.beingbmc.aigateway.dto;

import java.util.Map;

public record DateCounterCommentRequest(String eventId,
                                        String content,
                                        String author,
                                        Map<String, Object> metadata) {
}
