package com.example.videolingo.entity;

// What an AI call was for — the dimension usage and cost are broken down by.
// LOOKUP = a learner looking up a word from a subtitle (cached per word).
public enum AiFeature {
    SUMMARY,
    CHAPTERS,
    KEY_POINTS,
    QUESTIONS,
    QUIZ,
    CHAT,
    TRANSLATION,
    LOOKUP
}
