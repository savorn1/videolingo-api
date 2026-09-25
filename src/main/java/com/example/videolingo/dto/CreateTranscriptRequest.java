package com.example.videolingo.dto;

import com.example.videolingo.entity.TranscriptSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CreateTranscriptRequest {

    @NotNull
    private Long videoId;

    @NotBlank
    @Size(max = 10)
    private String language;

    // MANUAL or IMPORTED — AUTO is reserved for processing jobs.
    private TranscriptSource source = TranscriptSource.MANUAL;

    // May be empty: an empty transcript can be filled in later (or regenerated).
    @Valid
    @Size(max = 20000)
    private List<TranscriptSegmentDto> segments = new ArrayList<>();
}
