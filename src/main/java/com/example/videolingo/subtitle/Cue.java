package com.example.videolingo.subtitle;

// One timed subtitle cue. `text` may contain '\n' line breaks — lines are
// part of a subtitle (unlike a transcript segment), so they're kept as authored.
public record Cue(long startMs, long endMs, String text) {}
