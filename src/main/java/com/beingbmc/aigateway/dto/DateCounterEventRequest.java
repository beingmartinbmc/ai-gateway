package com.beingbmc.aigateway.dto;

import java.util.List;

public record DateCounterEventRequest(String title,
                                      String description,
                                      String eventDate,
                                      String category,
                                      Metadata metadata) {

    public record Metadata(List<String> labels,
                           String reaction,
                           String comments) {
    }
}
