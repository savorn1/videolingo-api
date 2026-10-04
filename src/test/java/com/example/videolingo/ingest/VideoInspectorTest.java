package com.example.videolingo.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VideoInspectorTest {

    @Test
    void cleansFacebookTitles() {
        assertArrayEquals(
                new String[] {"How to make coffee", "Cafe Lab"},
                VideoInspector.cleanFacebookTitle("1.2M views · 3K reactions | How to make coffee | By Cafe Lab"));
        assertArrayEquals(new String[] {"Plain title", null}, VideoInspector.cleanFacebookTitle("Plain title"));
        assertArrayEquals(
                new String[] {"How to share", null}, VideoInspector.cleanFacebookTitle("How to share | Facebook"));
        assertArrayEquals(new String[] {"A | B", null}, VideoInspector.cleanFacebookTitle("A | B"));
    }

    @Test
    void readsYouTubeAutoCaptionLanguage() {
        String html =
                "\"captionTracks\":[{\"baseUrl\":\"x\",\"name\":{},\"languageCode\":\"en\",\"isTranslatable\":true},"
                        + "{\"baseUrl\":\"y\",\"languageCode\":\"ja\",\"kind\":\"asr\",\"isTranslatable\":true}],\"audioTracks\"";
        assertEquals("ja", VideoInspector.asrLanguage(html));
        assertNull(VideoInspector.asrLanguage("\"captionTracks\":[{\"languageCode\":\"en\"}]"));
    }

    @Test
    void titlesFromFileNames() {
        assertEquals("Lesson 01 ordering coffee", VideoInspector.titleFromFileName("lesson_01-ordering.coffee.mp4"));
        assertNull(VideoInspector.titleFromFileName(".mp4"));
    }

    @Test
    void isoDurations() {
        assertEquals(214, Html.isoDuration("PT3M34S"));
        assertEquals(3723, Html.isoDuration("PT1H2M3S"));
        assertNull(Html.isoDuration("soon"));
    }
}
