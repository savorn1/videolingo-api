package com.example.videolingo.dto;

import com.example.videolingo.glossary.GlossaryEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

public final class GlossaryDtos {

    private GlossaryDtos() {}

    @Data
    @ParameterObject
    public static class GlossaryFilter {
        // Name or description.
        private String search;
        private String sourceLanguage;
        private String targetLanguage;
        private Boolean enabled;

        private String sortBy = "name";
        private String sortOrder = "asc";
        private int page = 1;
        private int size = 25;
    }

    @Data
    public static class TermDto {
        private Long id;

        @NotBlank
        @Size(max = 200)
        private String source;
        // Ignored (set to `source`) when doNotTranslate.
        @Size(max = 200)
        private String target;

        private boolean doNotTranslate;
        private boolean caseSensitive;

        @Size(max = 300)
        private String note;
    }

    // Full replacement: `terms` replaces every term of the glossary.
    @Data
    public static class GlossaryRequest {
        @NotBlank
        @Size(max = 100)
        private String name;
        // Blank = any source language.
        @Size(max = 10)
        private String sourceLanguage;

        @NotBlank
        @Size(max = 10)
        private String targetLanguage;

        @Size(max = 300)
        private String description;

        @NotNull
        private Boolean enabled = true;

        @Valid
        @NotNull
        @Size(max = 2000)
        private List<TermDto> terms = new ArrayList<>();
    }

    public record GlossaryResponse(
            Long id,
            String name,
            String sourceLanguage,
            String targetLanguage,
            String description,
            boolean enabled,
            int termCount,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            // Detail only.
            List<TermDto> terms) {}

    // What the subtitle editor checks cues against: the merged terms of every
    // enabled glossary into that language, each tagged with where it came from.
    public record ApplicableTerm(
            String source,
            String target,
            boolean doNotTranslate,
            boolean caseSensitive,
            String note,
            Long glossaryId,
            String glossaryName) {

        public GlossaryEntry entry() {
            return new GlossaryEntry(source, target, doNotTranslate, caseSensitive, note);
        }
    }
}
