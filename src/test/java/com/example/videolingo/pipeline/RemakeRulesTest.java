package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemakeRulesTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode read(String s) throws Exception {
        return json.readTree(s);
    }

    @Test
    void onlyARenderFromAudioCanBeMadeAgain() throws Exception {
        assertTrue(RemakeRules.isFromAudio(
                read("{\"operation\":\"AUDIO_TO_VIDEO\",\"audioKey\":\"audio-uploads/a.mp3\"}")));
        assertFalse(RemakeRules.isFromAudio(read("{\"operation\":\"TRIM\"}")));
        assertFalse(RemakeRules.isFromAudio(null));
        assertThrows(IllegalArgumentException.class, () -> RemakeRules.remakeParams(read("{\"operation\":\"MERGE\"}")));
    }

    @Test
    void theSettingsAreKeptButTheTranscriptIsNotRedone() throws Exception {
        JsonNode original = read("""
                {"operation":"AUDIO_TO_VIDEO","audioKey":"audio-uploads/a.mp3","resolution":"1080p","waveform":"BARS",
                 "transcribe":true,"burnSubtitles":{"style":"BOX","position":"BOTTOM"},"summary":"Bars, 1080p"}
                """);
        JsonNode again = RemakeRules.remakeParams(original);
        assertEquals("1080p", again.path("resolution").asText());
        assertEquals("BARS", again.path("waveform").asText());
        assertTrue(again.path("remake").asBoolean());
        assertTrue(again.path("transcribe").isMissingNode());
        assertTrue(again.path("burnSubtitles").isMissingNode());
        // The original is left as it was.
        assertTrue(original.path("transcribe").asBoolean());
    }

    @Test
    void theUploadsItNeedsAreTheSoundCoverPicturesAndLogo() throws Exception {
        JsonNode params = read("""
                {"operation":"AUDIO_TO_VIDEO","audioKey":"audio-uploads/a.mp3","coverKey":"overlay-uploads/c.jpg",
                 "slides":[{"key":"overlay-uploads/c.jpg","startMs":0},{"key":"overlay-uploads/d.jpg","startMs":5000}],
                 "logoKey":"overlay-uploads/logo.png"}
                """);
        assertEquals(
                List.of(
                        "audio-uploads/a.mp3",
                        "overlay-uploads/c.jpg",
                        "overlay-uploads/d.jpg",
                        "overlay-uploads/logo.png"),
                RemakeRules.uploadKeys(params));
        assertEquals(
                List.of("audio-uploads/a.mp3"),
                RemakeRules.uploadKeys(read("{\"audioKey\":\"audio-uploads/a.mp3\",\"coverKey\":\"\"}")));
    }
}
