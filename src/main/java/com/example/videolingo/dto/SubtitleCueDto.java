package com.example.videolingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleCueDto {

    private Long id;

    @NotNull
    @PositiveOrZero
    private Long startMs;

    @NotNull
    @PositiveOrZero
    private Long endMs;

    // May contain '\n' line breaks.
    @NotBlank
    @Size(max = 500)
    private String text;
}
