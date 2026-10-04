package com.example.videolingo.ai;

import com.example.videolingo.entity.AiFeature;

// Human names for AI features, for messages shown to admins.
public final class AiFeatureNames {

    private AiFeatureNames() {}

    public static String label(AiFeature feature) {
        return switch (feature) {
            case SUMMARY -> "Summary";
            case CHAPTERS -> "Chapters";
            case KEY_POINTS -> "Key points";
            case QUESTIONS -> "Questions";
            case QUIZ -> "Quiz";
            case CHAT -> "AI chat";
            case TRANSLATION -> "Translation";
            case LOOKUP -> "Word lookup";
        };
    }
}
