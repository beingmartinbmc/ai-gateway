package com.beingbmc.aigateway.dto;

import java.util.List;

public record ConversationListResponse(List<ConversationResponse> conversations,
                                       Pagination pagination) {

    public record Pagination(int currentPage,
                             int totalPages,
                             long totalCount,
                             int limit,
                             long skip,
                             boolean hasNextPage,
                             boolean hasPrevPage,
                             Integer nextPage,
                             Integer prevPage) {
    }
}
