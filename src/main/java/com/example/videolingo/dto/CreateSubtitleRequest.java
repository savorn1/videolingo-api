package com.example.videolingo.dto;

import com.example.videolingo.entity.SubtitleKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

// Either generate from a transcript (transcriptId set — the track takes the
// transcript's language), or create an empty manual track (language required).
@Data
public class CreateSubtitleRequest {

    @NotNull
    private Long videoId;

    private Long transcriptId;

    @Size(max = 10)
    private String language;

    // Defaults to the language's name, e.g. "Japanese".
    @Size(max = 100)
    private String label;

    private SubtitleKind kind = SubtitleKind.SUBTITLES;

    // Defaults to the Settings › Translation rules for the language.
    @Valid
    private SubtitleRulesDto rules;
}
