package com.example.videolingo.entity;

public enum TranscriptSource {
    // Produced by a TRANSCRIBE/TRANSLATE processing job.
    AUTO,
    // Typed in (or edited) by an admin.
    MANUAL,
    // Uploaded from a subtitle file (SRT/VTT/plain text).
    IMPORTED
}
