package com.beingbmc.aigateway.dto;

import java.util.List;

public record DateCounterListResponse<T>(boolean success,
                                         List<T> data,
                                         Pagination pagination) {

    public record Pagination(long total,
                             int limit,
                             int skip,
                             boolean hasMore) {
    }
}
