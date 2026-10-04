package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record QuizOutput(List<QuizQuestion> questions) {
    public record QuizQuestion(
            @JsonPropertyDescription("The question. Answerable from the video alone.")
            String question,

            @JsonPropertyDescription(
                    "3 or 4 answer options. Exactly one is correct; the others are plausible but wrong.")
            List<String> options,

            @JsonPropertyDescription("0-based index into options of the single correct answer.")
            int correctOptionIndex,

            @JsonPropertyDescription("Why the correct answer is right, referring to what was said.")
            String explanation,

            @JsonPropertyDescription("Where in the video the answer is found, in whole seconds.")
            int timestampSeconds) {}
}
