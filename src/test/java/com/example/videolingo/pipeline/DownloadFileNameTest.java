package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadFileNameTest {

    @Test
    void keepsTheTitleButDropsCharactersFilesCantHave() {
        assertEquals("Lesson 1 Greetings  part 2.mp4".replace("  ", " "), PipelineSteps.fileName("Lesson 1: Greetings / part 2", null));
    }

    @Test
    void marksTheVoiceOverLanguage() {
        assertEquals("រឿង Novel (km).mp4", PipelineSteps.fileName("រឿង Novel", "km"));
    }

    @Test
    void fallsBackWhenThereIsNoUsableTitle() {
        assertEquals("video.mp4", PipelineSteps.fileName("???", null));
        assertEquals("video.mp4", PipelineSteps.fileName(null, null));
    }

    @Test
    void marksBurnedInSubtitles() {
        assertEquals("Lesson (en subs).mp4", PipelineSteps.fileName("Lesson", null, "en"));
        assertEquals("Lesson (km, en subs).mp4", PipelineSteps.fileName("Lesson", "km", "en"));
    }

    @Test
    void escapesPathsForTheSubtitlesFilter() {
        assertEquals("/tmp/a\\:b\\'c.srt", MediaTools.filterPath(java.nio.file.Path.of("/tmp/a:b'c.srt")));
    }
}
