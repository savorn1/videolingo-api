package com.example.videolingo.dto;

import com.example.videolingo.entity.SubtitleKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

// Full replacement of the editable fields. `cues` null = leave cues as they
// are (e.g. only toggling published or renaming); a list replaces them all.
@Data
public class UpdateSubtitleRequest {

    @NotNull
    private Long version;

    @NotBlank
    @Size(max = 100)
    private String label;

    @NotBlank
    @Size(max = 10)
    private String language;

    @NotNull
    private SubtitleKind kind;

    @NotNull
    private Boolean published;

    @Valid
    @NotNull
    private SubtitleRulesDto rules;

    @Valid
    @Size(max = 20000)
    private List<SubtitleCueDto> cues;
}
