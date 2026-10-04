package com.example.videolingo.dto;

import java.math.BigDecimal;
import java.util.Map;

// `configured` = an API key is set; `enabled` = switched on in Settings.
// `features` maps SUMMARY…CHAT to whether each is switched on.
public record AiStatusResponse(
        boolean configured,
        boolean enabled,
        Map<String, Boolean> features,
        Map<String, Integer> defaultCounts,
        int maxChatMessageChars,
        String model,
        String fallbackModel,
        String generationEffort,
        String chatEffort,
        BigDecimal monthSpendUsd,
        BigDecimal monthlyBudgetUsd,
        boolean budgetEnforced,
        boolean budgetExceeded,
        Map<String, Map<String, BigDecimal>> pricing) {}
