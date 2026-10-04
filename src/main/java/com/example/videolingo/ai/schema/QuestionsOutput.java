package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

// Open-ended comprehension / discussion questions (the quiz is multiple choice).
public record QuestionsOutput(List<Question> questions) {
    public enum Difficulty {
        EASY,
        MEDIUM,
        HARD
    }

    public record Question(
            @JsonPropertyDescription("An open-ended comprehension or discussion question.") String question,
            @JsonPropertyDescription("A model answer, grounded in the transcript.") String answer,
            Difficulty difficulty,
            @JsonPropertyDescription("Where in the video the answer is found, in whole seconds.")
                    int timestampSeconds) {}
}
