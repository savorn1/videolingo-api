package com.example.videolingo.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

// The stored parameters of TRIM / SPLIT / CUT edit jobs, written by VideoEditService and read back by
// PipelineSteps through the same records — so a field can't be written under one name and read under
// another. AUDIO / OVERLAY / EXTRACT keep their spec under a key of the job's JSON (see PipelineSteps).
public final class EditJobParams {

    private EditJobParams() {}

    /** A range of the video; endMs null = to the end. */
    public record Range(long startMs, Long endMs) {}

    /** rotate/flips/audio/padMs/look/effect/fade/freeze/blurs/speed/pip/cards are left out when unused. padMs = time held past the video's end. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trim(
            String operation,
            long startMs,
            Long endMs,
            MediaTools.CropRect crop,
            MediaTools.ScaleSize scale,
            Integer rotate,
            Boolean flipH,
            Boolean flipV,
            AudioEditRules.Spec audio,
            Long padMs,
            VideoEditRules.Look look,
            String effect,
            VideoEditRules.Fade fade,
            VideoEditRules.Freeze freeze,
            List<VideoEditRules.BlurBox> blurs,
            VideoEditRules.SpeedRange speed,
            VideoEditRules.Pip pip,
            VideoEditRules.Cards cards) {}

    public record Split(String operation, List<Range> segments) {}

    /** Merged and sorted. */
    public record Cut(String operation, List<Range> cuts) {}

    /**
     * Reads job parameters. Unknown fields are skipped, so a job queued by an older or newer build still runs
     * (a job saved with an extra derived field — like a record's isXxx() — is what this guards against).
     */
    static ObjectMapper reader(ObjectMapper base) {
        return base.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
