package com.example.videolingo.service.impl;

import com.example.videolingo.entity.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Pure formatting helpers behind transcript export and stats — no Spring context.
class TranscriptFormatTest {

    private static TranscriptSegment seg(long start, long end, String text, String speaker) {
        return TranscriptSegment.builder().startMs(start).endMs(end).text(text).speaker(speaker).build();
    }

    @Test
    void timestampsArePaddedAndUseTheRightFractionSeparator() {
        assertEquals("00:00:00,000", TranscriptServiceImpl.timestamp(0, ','));
        assertEquals("01:02:03.456", TranscriptServiceImpl.timestamp(3_723_456, '.'));
        assertEquals("10:00:00,001", TranscriptServiceImpl.timestamp(36_000_001, ','));
    }

    @Test
    void srtNumbersCuesFromOne() {
        String srt = TranscriptServiceImpl.toSrt(List.of(seg(0, 1500, "Hello", null), seg(1500, 3000, "World", "A")));
        assertEquals("1\n00:00:00,000 --> 00:00:01,500\nHello\n\n2\n00:00:01,500 --> 00:00:03,000\nWorld\n\n", srt);
    }

    @Test
    void vttHasHeaderAndVoiceTagForSpeakers() {
        String vtt = TranscriptServiceImpl.toVtt(List.of(seg(0, 1000, "Hi", "Ana")));
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v Ana>Hi\n\n", vtt);
    }

    @Test
    void plainTextPrefixesSpeakers() {
        assertEquals("Ana: Hi\nBye\n", TranscriptServiceImpl.toText(List.of(seg(0, 1, "Hi", "Ana"), seg(1, 2, "Bye", null))));
        assertEquals("", TranscriptServiceImpl.toText(List.of()));
    }

    @Test
    void wordsCountLetterRunsAndEachCjkCharacter() {
        assertEquals(4, TranscriptServiceImpl.countWords("I don't like rain."));
        assertEquals(3, TranscriptServiceImpl.countWords("café au lait"));
        // コーヒー = 4 katakana, を = 1 hiragana, ください = 4 hiragana
        assertEquals(9, TranscriptServiceImpl.countWords("コーヒーをください"));
        assertEquals(0, TranscriptServiceImpl.countWords("  ... !! "));
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertEquals("100\\% \\_done\\\\", TranscriptServiceImpl.escapeLike("100% _done\\"));
    }

    @Test
    void slugsAreFilenameSafe() {
        assertEquals("ordering-coffee-in-japanese", TranscriptServiceImpl.slug("[TEST] Ordering coffee — in Japanese!").replace("test-", ""));
        assertEquals("transcript", TranscriptServiceImpl.slug("!!!"));
        assertEquals("ភាសាខ្មែរ", TranscriptServiceImpl.slug("ភាសាខ្មែរ"));
    }
}
