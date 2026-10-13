package com.example.videolingo.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.videolingo.dto.TranscriptWord;
import java.util.List;
import org.junit.jupiter.api.Test;

class WordTimingsTest {

    @Test
    void eachWordGoesToTheSegmentItsMiddleFallsIn() {
        List<long[]> segments = List.of(new long[] {0, 2000}, new long[] {2500, 4000});
        List<TranscriptWord> words = List.of(
                new TranscriptWord(0, 400, "Hello"),
                new TranscriptWord(1900, 2700, " um"), // middle 2300: still the first segment
                new TranscriptWord(2800, 3200, "welcome"),
                new TranscriptWord(3300, 3400, " "));
        List<List<TranscriptWord>> out = WordTimings.assign(segments, words);
        assertEquals(
                List.of("Hello", "um"),
                out.get(0).stream().map(TranscriptWord::text).toList());
        assertEquals(
                List.of("welcome"),
                out.get(1).stream().map(TranscriptWord::text).toList());
    }

    @Test
    void onlyWordsInsideTheSegmentAreKept() {
        List<TranscriptWord> words = List.of(
                new TranscriptWord(900, 1200, "late"),
                new TranscriptWord(0, 300, "early"),
                new TranscriptWord(5000, 5200, "far"));
        assertEquals(
                List.of("early", "late"),
                WordTimings.clean(words, 0, 1000).stream()
                        .map(TranscriptWord::text)
                        .toList());
        assertNull(WordTimings.clean(List.of(), 0, 1000));
        assertNull(WordTimings.clean(null, 0, 1000));
    }

    @Test
    void anEditedSegmentIsADifferentOne() {
        assertEquals(WordTimings.key(0, 1000, "Hello "), WordTimings.key(0, 1000, "Hello"));
        assertNotEquals(WordTimings.key(0, 1000, "Hello"), WordTimings.key(0, 1000, "Hullo"));
        assertNotEquals(WordTimings.key(0, 1000, "Hello"), WordTimings.key(0, 1100, "Hello"));
    }
}
