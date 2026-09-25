package com.example.videolingo.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ApiResponse<T> {

    private String traceId;
    private int statusCode;
    private String message;
    private T data;

    public ApiResponse() {
        this.traceId = UUID.randomUUID().toString();
    }

    public ApiResponse(int statusCode, String message, T data) {
        this.traceId = UUID.randomUUID().toString();
        this.statusCode = statusCode;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }
}
