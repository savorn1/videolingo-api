package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record KeyPointsOutput(
        @JsonPropertyDescription("The most important points, in order of appearance.")
        List<KeyPoint> keyPoints) {
    public record KeyPoint(
            @JsonPropertyDescription("The point itself, as one short sentence.")
            String point,

            @JsonPropertyDescription("One or two sentences of explanation or context for a learner.")
            String explanation,

            @JsonPropertyDescription(
                    "Where in the video this comes up, in whole seconds, from the transcript timestamps.")
            int timestampSeconds) {}
}
