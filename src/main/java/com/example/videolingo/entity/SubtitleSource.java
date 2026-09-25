package com.example.videolingo.entity;

public enum SubtitleSource {
    // Built from a transcript by SubtitleSegmenter.
    GENERATED,
    // Parsed from an uploaded .srt/.vtt file.
    UPLOADED,
    // Typed in, or hand-edited after being generated/uploaded.
    MANUAL
}
