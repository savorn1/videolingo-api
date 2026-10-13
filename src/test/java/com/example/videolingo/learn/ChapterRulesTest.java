package com.example.videolingo.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.videolingo.entity.VideoMarker;
import com.example.videolingo.learn.ChapterRules.Chapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChapterRulesTest {

    private static VideoMarker marker(long atMs, String label, boolean chapter) {
        return VideoMarker.builder().atMs(atMs).label(label).chapter(chapter).build();
    }

    @Test
    void onlyChapterMarkersInTimeOrderWithinTheVideo() {
        List<Chapter> chapters = ChapterRules.fromMarkers(
                List.of(
                        marker(60_000, "Practice", true),
                        marker(5_000, "Note to self", false),
                        marker(0, "Greetings", true),
                        marker(60_000, "Practice again", true),
                        marker(700_000, "Past the end", true)),
                600_000L);
        assertEquals(List.of(new Chapter(0, "Greetings"), new Chapter(60_000, "Practice")), chapters);
    }

    @Test
    void anUnknownLengthKeepsEveryChapter() {
        assertEquals(
                1,
                ChapterRules.fromMarkers(List.of(marker(900_000, "Late", true)), null)
                        .size());
    }
}
