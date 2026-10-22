package com.example.videolingo.pipeline;

import java.util.List;

// Narration from text (the editor's Audio tab, "Speak a line"): a line spoken by the text-to-speech service and kept
// as an audio upload, which the editor then adds as a sound track.
public final class NarrationRules {

    public static final int MAX_TEXT = 1000;
    public static final double MIN_RATE = 0.75;
    public static final double MAX_RATE = 1.5;

    /** What to speak, with which voice (a TextToSpeechClient voice id) and how fast (1 = normal). */
    public record Request(String text, String voice, Double rate) {
        public double speed() {
            return rate == null ? 1 : rate;
        }
    }

    private NarrationRules() {}

    /** Null when it can be spoken, else why not. */
    public static String validate(Request r, List<String> voiceIds) {
        if (r == null || r.text() == null || r.text().isBlank()) {
            return "Write the line to speak";
        }
        if (r.text().strip().length() > MAX_TEXT) {
            return "A line can be at most " + MAX_TEXT + " characters";
        }
        if (r.voice() == null || !voiceIds.contains(r.voice())) {
            return "Pick a voice";
        }
        if (r.speed() < MIN_RATE || r.speed() > MAX_RATE) {
            return "The speed is between " + MIN_RATE + "× and " + MAX_RATE + "×";
        }
        return null;
    }

    /** The upload's name: under the audio uploads (so it is cleaned up with them), and unique. */
    public static String key(String uuid) {
        return AudioEditRules.UPLOAD_PREFIX + "narration-" + uuid + ".wav";
    }
}
