package com.example.videolingo.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.pipeline.AudioToVideoRules;
import org.junit.jupiter.api.Test;

class ClientSettingsControllerTest {

    @Test
    void mediaRulesMirrorTheRulesTheToolsEnforce() {
        ClientSettingsController.MediaRules r = ClientSettingsController.MEDIA_RULES;
        assertEquals(20, r.maxSegments());
        assertEquals(3, r.maxQueuedEdits());
        assertEquals(50, r.maxAudioClips());
        assertEquals(10, r.maxMergeVideos());
        assertEquals(3L * 60 * 60 * 1000, r.maxMergeMs());
        assertEquals(AudioToVideoRules.MAX_SLIDES, r.maxSlides());
        assertEquals(AudioToVideoRules.MAX_STRIP, r.maxStrip());
        assertTrue(r.waveforms().contains("NONE") && r.waveforms().contains("STEREO"));
    }
}
