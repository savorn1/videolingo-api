package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationRulesTest {

    private static final List<String> VOICES = List.of("nova", "onyx");

    @Test
    void aLineWithAKnownVoiceAtASensibleSpeedIsSpoken() {
        assertNull(
                NarrationRules.validate(new NarrationRules.Request("Welcome to lesson four.", "nova", null), VOICES));
        assertNull(NarrationRules.validate(new NarrationRules.Request("Slowly now.", "onyx", 0.75), VOICES));
    }

    @Test
    void itNeedsTextAVoiceAndASpeedInRange() {
        assertEquals(
                "Write the line to speak",
                NarrationRules.validate(new NarrationRules.Request("  ", "nova", 1.0), VOICES));
        assertEquals(
                "A line can be at most 1000 characters",
                NarrationRules.validate(new NarrationRules.Request("a".repeat(1001), "nova", 1.0), VOICES));
        assertEquals("Pick a voice", NarrationRules.validate(new NarrationRules.Request("Hi", "robot", 1.0), VOICES));
        assertEquals(
                "The speed is between 0.75× and 1.5×",
                NarrationRules.validate(new NarrationRules.Request("Hi", "nova", 3.0), VOICES));
    }

    @Test
    void itIsKeptWithTheAudioUploads() {
        String key = NarrationRules.key("abc");
        assertEquals("audio-uploads/narration-abc.wav", key);
        assertTrue(AudioEditRules.isUploadKey(key), "usable as a sound track");
    }
}
