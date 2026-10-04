package com.example.videolingo.subtitle;

// A readability problem with one cue. `cueIndex` is 0-based.
public record SubtitleIssue(int cueIndex, Type type, String message) {

    public enum Type {
        LINE_TOO_LONG,
        TOO_MANY_LINES,
        TOO_FAST,
        TOO_SHORT,
        TOO_LONG,
        OVERLAP
    }
}
