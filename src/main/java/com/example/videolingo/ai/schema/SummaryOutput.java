package com.example.videolingo.ai.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

// Structured-output schema for "Generate Summary". Field descriptions are sent
// to Claude as part of the JSON schema.
public record SummaryOutput(
        @JsonPropertyDescription("One sentence (max ~25 words) saying what the video is about.") String tldr,
        @JsonPropertyDescription(
                        "A 2–4 paragraph summary of the video's content, in plain prose. Separate paragraphs with a blank line.")
                String summary,
        @JsonPropertyDescription("3–8 short topic labels covered in the video, e.g. \"ordering drinks\".")
                List<String> topics,
        @JsonPropertyDescription("Estimated CEFR level of the spoken language in the video for a learner.")
                CefrLevel estimatedLevel) {
    public enum CefrLevel {
        A1,
        A2,
        B1,
        B2,
        C1,
        C2
    }
}
