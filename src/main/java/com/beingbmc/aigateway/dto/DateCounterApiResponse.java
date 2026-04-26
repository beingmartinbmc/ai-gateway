package com.beingbmc.aigateway.dto;

public record DateCounterApiResponse<T>(boolean success,
                                        String message,
                                        T data) {

    public static <T> DateCounterApiResponse<T> ok(T data) {
        return new DateCounterApiResponse<>(true, null, data);
    }

    public static <T> DateCounterApiResponse<T> ok(String message, T data) {
        return new DateCounterApiResponse<>(true, message, data);
    }
}
