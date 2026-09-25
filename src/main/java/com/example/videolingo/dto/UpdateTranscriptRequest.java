package com.example.videolingo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

// Full replacement of language + segments. `version` is the one the client
// loaded; a stale version is rejected rather than silently overwriting.
@Data
public class UpdateTranscriptRequest {

    @NotNull
    private Long version;

    @NotBlank
    @Size(max = 10)
    private String language;

    @Valid
    @NotNull
    @Size(max = 20000)
    private List<TranscriptSegmentDto> segments = new ArrayList<>();
}
