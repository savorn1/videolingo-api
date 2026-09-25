package com.example.videolingo.dto;

import jakarta.validation.Valid;
import lombok.Data;

// Both optional: omit to regenerate from the same transcript with the track's current rules.
@Data
public class RegenerateSubtitleRequest {

    private Long transcriptId;

    @Valid
    private SubtitleRulesDto rules;
}
