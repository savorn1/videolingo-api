package com.example.videolingo.pipeline;

import java.util.ArrayList;
import java.util.List;

// "Remove the pauses": what counts as quiet when looking for pauses in a video's sound. The editor
// decides which pauses to cut (and how much quiet to keep next to the speech); this only finds them.
public final class PauseRules {

    /** Quieter than this (dB) is a pause; -40 suits a quiet room, -30 a noisier one. */
    public static final int DEFAULT_NOISE_DB = -40;

    public static final int MIN_NOISE_DB = -60;
    public static final int MAX_NOISE_DB = -20;

    /** The shortest pause reported; the editor's own "at least" slider starts here. */
    public static final long MIN_PAUSE_MS = 300;

    private PauseRules() {}

    /** Null when `noiseDb` is usable, else why not. */
    public static String validateNoise(int noiseDb) {
        return noiseDb < MIN_NOISE_DB || noiseDb > MAX_NOISE_DB
                ? "The quiet level must be between " + MIN_NOISE_DB + " and " + MAX_NOISE_DB + " dB"
                : null;
    }

    /** The pauses within the video, ends clamped to its length (silencedetect leaves a pause running to the end open). */
    public static List<AudioToVideoRules.Silence> clamp(List<AudioToVideoRules.Silence> silences, long durationMs) {
        List<AudioToVideoRules.Silence> out = new ArrayList<>();
        for (AudioToVideoRules.Silence s : silences) {
            long start = Math.max(0, s.startMs());
            long end = durationMs > 0 ? Math.min(durationMs, s.endMs()) : s.endMs();
            if (end - start >= MIN_PAUSE_MS) {
                out.add(new AudioToVideoRules.Silence(start, end));
            }
        }
        return out;
    }
}
