package com.example.videolingo.entity;

public enum AiUsageStatus {
    SUCCESS,
    // Claude declined (stop_reason "refusal"); may be followed by a fallback call.
    REFUSED,
    // Output hit max_tokens and was unusable.
    TRUNCATED,
    // The API call failed (network, rate limit, bad request, unparseable output…).
    ERROR
}
