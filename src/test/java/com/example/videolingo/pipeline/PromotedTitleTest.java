package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.entity.VideoClip.Operation;
import org.junit.jupiter.api.Test;

class PromotedTitleTest {

    @Test
    void aGivenTitleIsUsedAsItIsButTrimmed() {
        assertEquals("My cut", VideoEditService.promotedTitle("Lesson", Operation.TRIM, null, "  My cut  "));
    }

    @Test
    void aBlankTitleFallsBackToTheDefault() {
        assertEquals("Lesson (trimmed)", VideoEditService.promotedTitle("Lesson", Operation.TRIM, null, "   "));
        assertEquals("Lesson (trimmed)", VideoEditService.promotedTitle("Lesson", Operation.TRIM, null, null));
    }

    @Test
    void eachKindOfResultSaysWhatWasDone() {
        assertEquals("Lesson (trimmed)", VideoEditService.promotedTitle("Lesson", Operation.TRIM, null, null));
        assertEquals("Lesson (edited audio)", VideoEditService.promotedTitle("Lesson", Operation.AUDIO, null, null));
        assertEquals(
                "Lesson (with text & overlays)",
                VideoEditService.promotedTitle("Lesson", Operation.OVERLAY, null, null));
        assertEquals("Lesson — Part 3", VideoEditService.promotedTitle("Lesson", Operation.SPLIT, 2, null));
        assertEquals("Lesson — Part 1", VideoEditService.promotedTitle("Lesson", Operation.SPLIT, null, null));
    }

    @Test
    void aLongTitleIsShortenedSoTheSuffixSurvives() {
        String title = VideoEditService.promotedTitle("x".repeat(200), Operation.TRIM, null, null);
        assertEquals(VideoEditService.MAX_TITLE, title.length());
        assertTrue(title.endsWith("…" + " (trimmed)"), title);
    }

    @Test
    void aLongGivenTitleIsCut() {
        assertEquals(
                VideoEditService.MAX_TITLE,
                VideoEditService.promotedTitle("Lesson", Operation.TRIM, null, "y".repeat(500))
                        .length());
    }

    @Test
    void aMissingOriginalTitleStillGivesATitle() {
        assertEquals("Video (trimmed)", VideoEditService.promotedTitle(null, Operation.TRIM, null, null));
    }

    @Test
    void downloadOnlyResultsAreKnown() {
        org.junit.jupiter.api.Assertions.assertTrue(
                com.example.videolingo.entity.VideoClip.Operation.GIF.downloadOnly());
        org.junit.jupiter.api.Assertions.assertTrue(
                com.example.videolingo.entity.VideoClip.Operation.STILL.downloadOnly());
        org.junit.jupiter.api.Assertions.assertTrue(
                com.example.videolingo.entity.VideoClip.Operation.EXTRACT.downloadOnly());
        org.junit.jupiter.api.Assertions.assertFalse(
                com.example.videolingo.entity.VideoClip.Operation.TRIM.downloadOnly());
    }
}
