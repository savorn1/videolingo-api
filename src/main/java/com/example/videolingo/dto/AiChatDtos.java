package com.example.videolingo.dto;

import com.example.videolingo.entity.AiChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AiChatDtos {

    private AiChatDtos() {
    }

    @Data
    public static class CreateChatRequest {
        @NotNull
        private Long videoId;
        // Defaults to the spoken-language transcript.
        private Long transcriptId;
    }

    @Data
    public static class SendMessageRequest {
        // Length is capped by ai.max-chat-message-chars in the service.
        @NotBlank
        private String content;
    }

    public record MessageDto(Long id, AiChatMessage.Role role, String content, LocalDateTime createdAt, AiUsageDto usage) {
    }

    public record ChatDto(Long id, Long videoId, String videoTitle, Long transcriptId, String transcriptLanguage, String title,
                          int messageCount, BigDecimal totalCostUsd, String createdBy, LocalDateTime createdAt,
                          LocalDateTime updatedAt, List<MessageDto> messages) {
    }

    // Result of one chat turn: the stored user + assistant messages.
    public record TurnDto(ChatDto chat, MessageDto userMessage, MessageDto assistantMessage) {
    }
}
