package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJobType;

import java.util.EnumSet;
import java.util.Set;

// The worker runs one job at a time *per lane*. Lanes keep long jobs of one
// kind from holding up the others: a transcription waiting on the speech-to-text
// service shouldn't block a quick trim, and a long video join shouldn't block
// translations. Within a lane jobs still run in order, one after another.
public enum JobLane {

    /** Work done here with ffmpeg / yt-dlp: edits, joins, downloads. */
    MEDIA(EnumSet.of(ProcessingJobType.EDIT, ProcessingJobType.DOWNLOAD)),
    /** Work that mostly waits on an AI service: transcripts, translations, voice-overs. */
    AI(EnumSet.of(ProcessingJobType.TRANSCRIBE, ProcessingJobType.TRANSLATE, ProcessingJobType.DUB));

    private final Set<ProcessingJobType> types;

    JobLane(Set<ProcessingJobType> types) {
        this.types = types;
    }

    public Set<ProcessingJobType> types() {
        return types;
    }

    /** The lane a job type runs in, or null for a type this worker doesn't handle. */
    public static JobLane of(ProcessingJobType type) {
        for (JobLane lane : values()) {
            if (lane.types.contains(type)) {
                return lane;
            }
        }
        return null;
    }
}
