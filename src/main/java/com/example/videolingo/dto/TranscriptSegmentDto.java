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
public class TranscriptSegmentDto {

    // Ignored on input (segments are replaced wholesale); set on output.
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long startMs;

    @NotNull
    @PositiveOrZero
    private Long endMs;

    @NotBlank
    @Size(max = 2000)
    private String text;

    @Size(max = 64)
    private String speaker;
}
