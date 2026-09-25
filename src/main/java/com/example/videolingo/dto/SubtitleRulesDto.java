package com.example.videolingo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleRulesDto {

    @NotNull @Min(10) @Max(100)
    private Integer maxCharsPerLine;

    @NotNull @Min(1) @Max(4)
    private Integer maxLines;

    @NotNull @Min(0) @Max(10_000)
    private Long minDurationMs;

    @NotNull @Min(1000) @Max(60_000)
    private Long maxDurationMs;

    @NotNull @DecimalMin("1.0") @DecimalMax("60.0")
    private Double maxCps;
}
