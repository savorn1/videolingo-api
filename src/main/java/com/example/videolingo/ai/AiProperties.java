package com.example.videolingo.ai;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the Claude-backed AI features (see application.properties "ai.*").
@ConfigurationProperties("ai")
public record AiProperties(
        String apiKey,
        String baseUrl,
        String model,
        String fallbackModel,
        String generationEffort,
        String chatEffort,
        int maxTranscriptChars,
        int maxChatMessageChars,
        BigDecimal monthlyBudgetUsd,
        boolean budgetEnforced,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        Map<String, ModelPrice> pricing) {

    public record ModelPrice(BigDecimal inputPerMtok, BigDecimal outputPerMtok) {}

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasFallback() {
        return fallbackModel != null && !fallbackModel.isBlank() && !fallbackModel.equals(model);
    }
}
