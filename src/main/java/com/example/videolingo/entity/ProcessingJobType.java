package com.example.videolingo.entity;

// The pipeline stages a video goes through after upload.
public enum ProcessingJobType {
    // Re-encode into streamable renditions.
    TRANSCODE,
    // Speech-to-text in the video's spoken language.
    TRANSCRIBE,
    // Translate the transcript into a target language (see parameters).
    TRANSLATE,
    // Build timed subtitle tracks from the transcript/translation.
    GENERATE_SUBTITLES,
    GENERATE_THUMBNAIL,
    // Voice-over in another language: transcribe and translate as needed,
    // then synthesize speech for each segment (see parameters).
    DUB,
    // Fetch a link video as an MP4 (optionally with a voice-over as its
    // sound) — as a temporary download, or imported to replace the link.
    DOWNLOAD
}
