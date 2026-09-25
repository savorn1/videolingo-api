package com.example.videolingo.dto;

import com.example.videolingo.entity.AiFeature;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// `content` is the validated structured output (shape depends on `type`).
public record AiGenerationResponse(Long id, Long videoId, Long transcriptId, String transcriptLanguage, AiFeature type,
                                   String outputLanguage, String model, Map<String, Object> content, int itemCount,
                                   List<String> warnings, String createdBy, LocalDateTime createdAt, AiUsageDto usage) {
}
