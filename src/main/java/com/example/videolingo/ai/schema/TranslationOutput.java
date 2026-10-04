package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record TranslationOutput(
        @JsonPropertyDescription("One entry per input line, same indexes, same order.")
        List<Line> lines) {
    public record Line(
            @JsonPropertyDescription("The input line's index.")
            int index,

            @JsonPropertyDescription("The translated line.") String text) {}
}
