package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoEditRulesTest {

    @Test
    void trimAcceptsAValidRange() {
        assertNull(VideoEditRules.validateTrim(1000, 5000L, 10000L));
        assertNull(VideoEditRules.validateTrim(1000, null, 10000L));
        assertNull(VideoEditRules.validateTrim(0, 100L, null));
    }

    @Test
    void trimRejectsOutOfBoundsOrBackwardsRanges() {
        assertNotNull(VideoEditRules.validateTrim(-1, 100L, null));
        assertNotNull(VideoEditRules.validateTrim(500, 500L, null));
        assertNotNull(VideoEditRules.validateTrim(500, 400L, null));
        assertNotNull(VideoEditRules.validateTrim(10000, 12000L, 10000L));
        assertNotNull(VideoEditRules.validateTrim(1000, 20000L, 10000L));
    }

    @Test
    void cropMustFitInsideTheVideo() {
        assertNull(VideoEditRules.validateCrop(0, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(0, 0, 0, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(-1, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(1000, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(0, 500, 640, 480, 1280, 720));
        // Unknown video dimensions: only positivity is checked.
        assertNull(VideoEditRules.validateCrop(0, 0, 640, 480, null, null));
    }

    @Test
    void scaleMustBePositive() {
        assertNull(VideoEditRules.validateScale(640, 480));
        assertNotNull(VideoEditRules.validateScale(0, 480));
        assertNotNull(VideoEditRules.validateScale(640, -1));
    }

    @Test
    void segmentsNeedAtLeastOneAndAtMostTheCap() {
        assertNotNull(VideoEditRules.validateSegments(List.of(), null));
        assertNull(VideoEditRules.validateSegments(List.of(new VideoEditRules.Segment(0, 1000L)), 10000L));
        List<VideoEditRules.Segment> tooMany =
                java.util.Collections.nCopies(VideoEditRules.MAX_SEGMENTS + 1, new VideoEditRules.Segment(0, 100L));
        assertNotNull(VideoEditRules.validateSegments(tooMany, null));
    }

    @Test
    void segmentsValidateEachRangeIndividually() {
        var segments = List.of(new VideoEditRules.Segment(0, 1000L), new VideoEditRules.Segment(900, 500L));
        String error = VideoEditRules.validateSegments(segments, null);
        assertNotNull(error);
        assertTrue(error.contains("Segment 2"));
    }

    @Test
    void rotationIsAQuarterTurn() {
        assertNull(VideoEditRules.validateRotation(null));
        for (int d : new int[] {0, 90, 180, 270}) {
            assertNull(VideoEditRules.validateRotation(d));
        }
        assertNotNull(VideoEditRules.validateRotation(45));
        assertNotNull(VideoEditRules.validateRotation(-90));
        assertNotNull(VideoEditRules.validateRotation(360));
    }

    @Test
    void orientationIsOnlyAskedForWhenSomethingChanges() {
        assertFalse(VideoEditRules.hasOrientation(null, null, null));
        assertFalse(VideoEditRules.hasOrientation(0, false, false));
        assertTrue(VideoEditRules.hasOrientation(90, null, null));
        assertTrue(VideoEditRules.hasOrientation(null, true, null));
        assertTrue(VideoEditRules.hasOrientation(0, false, true));
        assertNull(VideoEditRules.describeOrientation(0, false, false));
    }

    @Test
    void quarterTurnsSwapTheSides() {
        assertTrue(VideoEditRules.swapsSides(90));
        assertTrue(VideoEditRules.swapsSides(270));
        assertFalse(VideoEditRules.swapsSides(180));
        assertFalse(VideoEditRules.swapsSides(0));
        assertFalse(VideoEditRules.swapsSides(null));
    }

    @Test
    void orientationIsDescribedInWords() {
        assertEquals("Turned 90° clockwise", VideoEditRules.describeOrientation(90, false, false));
        assertEquals("Flipped left–right", VideoEditRules.describeOrientation(null, true, false));
        assertEquals(
                "Turned 180° clockwise, flipped left–right, flipped top–bottom",
                VideoEditRules.describeOrientation(180, true, true));
    }

    @Test
    void anExtendedTrimMayRunPastTheEndButNotFar() {
        assertNull(VideoEditRules.validateExtendedTrim(1000, 70000L, 60000L));
        assertNull(VideoEditRules.validateExtendedTrim(1000, 30000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(1000, null, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(1000, 70000L, null));
        assertNotNull(VideoEditRules.validateExtendedTrim(60000, 70000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(5000, 5000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(0, 60000L + VideoEditRules.MAX_EXTEND_MS + 1, 60000L));
    }

    @Test
    void padFilterHoldsTheLastFrame() {
        assertEquals("tpad=stop_mode=clone:stop_duration=11.000", MediaTools.padFilter(10000));
    }

    @Test
    void aLookAndFadeSurviveTheJobParamsRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VideoEditRules.Look look = new VideoEditRules.Look(0.2, 1.1, 1, 0, false, true, false, 0.3, true);
        String lookJson = mapper.writeValueAsString(look);
        assertFalse(lookJson.contains("plain"));
        assertEquals(look, mapper.readValue(lookJson, VideoEditRules.Look.class));
        VideoEditRules.Fade fade = new VideoEditRules.Fade(500, 0, "black");
        String fadeJson = mapper.writeValueAsString(fade);
        assertFalse(fadeJson.contains("none"));
        assertEquals(fade, mapper.readValue(fadeJson, VideoEditRules.Fade.class));
        // Jobs queued before the fix still carry the derived flags.
        assertTrue(mapper.readValue(
                        "{\"brightness\":0,\"contrast\":1,\"saturation\":1,\"blur\":0,\"plain\":true}",
                        VideoEditRules.Look.class)
                .isPlain());
        assertTrue(mapper.readValue("{\"inMs\":0,\"outMs\":0,\"none\":true}", VideoEditRules.Fade.class)
                .isNone());
    }

    @Test
    void aLookIsCheckedAndDescribed() {
        VideoEditRules.Look plain = new VideoEditRules.Look(0, 1, 1, 0, false, false, false);
        assertTrue(plain.isPlain());
        assertNull(VideoEditRules.describeLook(plain));
        assertNull(VideoEditRules.validateLook(null));
        assertNull(VideoEditRules.validateLook(new VideoEditRules.Look(0.2, 1.4, 1.5, 3, false, true, true)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(1.5, 1, 1, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 3, 1, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 1, 5, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 1, 1, 50, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(Double.NaN, 1, 1, 0, false, false, false)));
        assertEquals(
                "Brighter, black & white, vignette",
                VideoEditRules.describeLook(new VideoEditRules.Look(0.1, 1, 1, 0, true, false, true)));
    }

    @Test
    void warmthAndSharpenArePartOfTheLook() {
        assertFalse(new VideoEditRules.Look(0, 1, 1, 0, false, false, false, 0.5, false).isPlain());
        assertFalse(new VideoEditRules.Look(0, 1, 1, 0, false, false, false, 0, true).isPlain());
        assertNotNull(
                VideoEditRules.validateLook(new VideoEditRules.Look(0, 1, 1, 0, false, false, false, 1.5, false)));
        assertEquals(
                "Warmer, sharpened",
                VideoEditRules.describeLook(new VideoEditRules.Look(0, 1, 1, 0, false, false, false, 0.4, true)));
        assertEquals(
                "Cooler",
                VideoEditRules.describeLook(new VideoEditRules.Look(0, 1, 1, 0, false, false, false, -0.4, false)));
    }

    @Test
    void effectsComeFromTheList() {
        assertNull(VideoEditRules.validateEffect(null));
        assertNull(VideoEditRules.validateEffect("ZOOM_IN"));
        assertNotNull(VideoEditRules.validateEffect("EXPLODE"));
        assertEquals("old film", VideoEditRules.describeEffect("OLD_FILM"));
        assertNull(VideoEditRules.describeEffect("NONE"));
    }

    @Test
    void fadesStayShortAndFitTheClip() {
        assertNull(VideoEditRules.validateFade(null, 1000L));
        assertNull(VideoEditRules.validateFade(new VideoEditRules.Fade(1000, 2000, "WHITE"), 3000L));
        assertNotNull(VideoEditRules.validateFade(new VideoEditRules.Fade(6000, 0, null), null));
        assertNotNull(VideoEditRules.validateFade(new VideoEditRules.Fade(-1, 0, null), null));
        assertNotNull(VideoEditRules.validateFade(new VideoEditRules.Fade(1000, 0, "PINK"), null));
        assertEquals(
                "The fade in and fade out together can't be longer than the clip",
                VideoEditRules.validateFade(new VideoEditRules.Fade(2000, 2000, null), 3000L));
        assertEquals(
                "fade in 1 s, fade out 0.5 s (white)",
                VideoEditRules.describeFade(new VideoEditRules.Fade(1000, 500, "WHITE")));
        assertNull(VideoEditRules.describeFade(new VideoEditRules.Fade(0, 0, null)));
    }

    @Test
    void aFreezeIsShortAndInsideTheClip() {
        assertNull(VideoEditRules.validateFreeze(null, 5000L));
        assertNull(VideoEditRules.validateFreeze(new VideoEditRules.Freeze(1000, 2000), 5000L));
        assertNotNull(VideoEditRules.validateFreeze(new VideoEditRules.Freeze(1000, 200), 5000L));
        assertNotNull(VideoEditRules.validateFreeze(new VideoEditRules.Freeze(1000, 20_000), 5000L));
        assertNotNull(VideoEditRules.validateFreeze(new VideoEditRules.Freeze(0, 2000), 5000L));
        assertNotNull(VideoEditRules.validateFreeze(new VideoEditRules.Freeze(5000, 2000), 5000L));
        assertEquals("frame held 2 s", VideoEditRules.describeFreeze(new VideoEditRules.Freeze(1000, 2000)));
    }

    @Test
    void aGifIsShortAndAStillIsInsideTheVideo() {
        assertNull(VideoEditRules.validateGif(1000, 5000, 480, 10_000L));
        assertNotNull(VideoEditRules.validateGif(0, 20_000, 480, 60_000L));
        assertNotNull(VideoEditRules.validateGif(0, 3000, 500, 10_000L));
        assertNotNull(VideoEditRules.validateGif(5000, 4000, 480, 10_000L));
        assertNull(VideoEditRules.validateStill(0, 10_000L));
        assertNotNull(VideoEditRules.validateStill(10_000, 10_000L));
        assertNotNull(VideoEditRules.validateStill(-1, null));
    }

    @Test
    void blurBoxesStayInsideThePictureAndAreFew() {
        assertNull(VideoEditRules.validateBlurs(null, 1920, 1080));
        assertNull(VideoEditRules.validateBlurs(List.of(new VideoEditRules.BlurBox(0, 0, 100, 100)), 1920, 1080));
        assertNotNull(VideoEditRules.validateBlurs(List.of(new VideoEditRules.BlurBox(0, 0, 4, 100)), 1920, 1080));
        assertNotNull(VideoEditRules.validateBlurs(List.of(new VideoEditRules.BlurBox(1900, 0, 100, 100)), 1920, 1080));
        assertNotNull(VideoEditRules.validateBlurs(
                java.util.Collections.nCopies(6, new VideoEditRules.BlurBox(0, 0, 50, 50)), 1920, 1080));
    }

    @Test
    void aSpeedRangeIsLongEnoughUsesAKnownSpeedAndMovesLaterTimes() {
        VideoEditRules.SpeedRange slow = new VideoEditRules.SpeedRange(1000, 3000, 0.5);
        assertNull(VideoEditRules.validateSpeedRange(slow, 10_000L));
        assertNotNull(VideoEditRules.validateSpeedRange(new VideoEditRules.SpeedRange(1000, 1200, 0.5), 10_000L));
        assertNotNull(VideoEditRules.validateSpeedRange(new VideoEditRules.SpeedRange(1000, 3000, 0.3), 10_000L));
        assertNotNull(VideoEditRules.validateSpeedRange(new VideoEditRules.SpeedRange(1000, 12_000, 2.0), 10_000L));
        assertEquals(500, VideoEditRules.rampedMs(500, slow));
        assertEquals(3000, VideoEditRules.rampedMs(2000, slow));
        assertEquals(6000, VideoEditRules.rampedMs(4000, slow));
        assertEquals(4000, VideoEditRules.rampedMs(4000, null));
        assertEquals("slow motion 0.5× for 2 s", VideoEditRules.describeSpeedRange(slow));
        assertEquals(
                "sped up 2× for 2 s", VideoEditRules.describeSpeedRange(new VideoEditRules.SpeedRange(0, 2000, 2.0)));
    }

    @Test
    void pictureInPictureAndCardsAreChecked() {
        assertNull(VideoEditRules.validatePip(new VideoEditRules.Pip(3L, "TOP_RIGHT", 30)));
        assertNotNull(VideoEditRules.validatePip(new VideoEditRules.Pip(null, "TOP_RIGHT", 30)));
        assertNotNull(VideoEditRules.validatePip(new VideoEditRules.Pip(3L, "MIDDLE", 30)));
        assertNotNull(VideoEditRules.validatePip(new VideoEditRules.Pip(3L, null, 80)));
        VideoEditRules.Card hi = new VideoEditRules.Card("Hello", 3000);
        assertNull(VideoEditRules.validateCards(new VideoEditRules.Cards(hi, null, "#000000", "#ffffff", null)));
        assertNotNull(VideoEditRules.validateCards(new VideoEditRules.Cards(null, null, "#000000", "#ffffff", null)));
        assertNotNull(VideoEditRules.validateCards(
                new VideoEditRules.Cards(new VideoEditRules.Card(" ", 3000), null, "#000000", "#ffffff", null)));
        assertNotNull(VideoEditRules.validateCards(
                new VideoEditRules.Cards(null, new VideoEditRules.Card("Bye", 9000), "#000000", "#ffffff", null)));
        assertNotNull(VideoEditRules.validateCards(new VideoEditRules.Cards(hi, null, "navy", "#ffffff", null)));
        assertNotNull(VideoEditRules.validateCards(
                new VideoEditRules.Cards(hi, null, "#000000", "#ffffff", "secrets/x.png")));
        assertEquals(
                "intro and outro cards",
                VideoEditRules.describeCards(new VideoEditRules.Cards(hi, hi, "#000000", "#ffffff", null)));
    }
}
