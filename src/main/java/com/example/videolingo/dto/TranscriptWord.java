package com.example.videolingo.dto;

/** One word of a transcript segment and when it is said (ms, on the video's timeline). */
public record TranscriptWord(long startMs, long endMs, String text) {}
