package com.example.videolingo.dto;

import com.example.videolingo.entity.AiFeature;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiGenerateRequest {

    // SUMMARY, CHAPTERS, KEY_POINTS, QUESTIONS or QUIZ (CHAT is rejected).
    @NotNull
    private AiFeature type;

    // Defaults to the spoken-language transcript.
    private Long transcriptId;

    // Language to write the output in; defaults to the transcript's.
    @Size(max = 10)
    private String outputLanguage;

    // Number of items for KEY_POINTS / QUESTIONS / QUIZ (defaults 6 / 5 / 8).
    @Min(1)
    @Max(20)
    private Integer count;
}
