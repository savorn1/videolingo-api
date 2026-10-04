package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record ChaptersOutput(
        @JsonPropertyDescription("Chapters in playback order. The first starts at 0.") List<Chapter> chapters) {
    public record Chapter(
            @JsonPropertyDescription("Start time in whole seconds, taken from the transcript timestamps.")
                    int startSeconds,
            @JsonPropertyDescription("Short chapter title (max ~8 words).") String title,
            @JsonPropertyDescription("One sentence describing the chapter.") String summary) {}
}
