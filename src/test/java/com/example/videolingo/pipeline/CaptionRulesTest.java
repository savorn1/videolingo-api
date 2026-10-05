package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CaptionRulesTest {

    private static CaptionRules.Line line(long a, long b, String text) {
        return new CaptionRules.Line(a, b, text);
    }

    @Test
    void pairsTheTranslationLinesThatBelongToEachLine() {
        List<CaptionRules.Cue> cues = CaptionRules.pair(
                List.of(line(0, 2000, "Hello there"), line(2000, 5000, "How are you?"), line(5000, 6000, " ")),
                List.of(line(0, 1000, "Bonjour"), line(900, 2100, "à vous"), line(2200, 4800, "Comment ça va ?")));
        assertEquals(2, cues.size());
        assertEquals("Bonjour à vous", cues.get(0).second());
        assertEquals("Comment ça va ?", cues.get(1).second());
    }

    @Test
    void fallsBackToTheMostOverlappingLineAndToNone() {
        List<CaptionRules.Cue> cues = CaptionRules.pair(
                List.of(line(1000, 2000, "Short"), line(9000, 9500, "Alone")),
                List.of(line(0, 10_000 - 500, "Long one")));
        assertEquals("Long one", cues.get(0).second());
        assertEquals("Long one", cues.get(1).second());
        assertNull(
                CaptionRules.pair(List.of(line(0, 1000, "A")), List.of()).get(0).second());
        assertNull(CaptionRules.pair(List.of(line(0, 1000, "A")), null).get(0).second());
    }

    @Test
    void writesAnAssFileSizedForTheVideo() {
        String ass = CaptionRules.toAss(
                List.of(new CaptionRules.Cue(1500, 3250, "Hi {there}", "Salut")), "BOX", "TOP", 5, 1920, 1080);
        assertTrue(ass.contains("PlayResX: 1920\nPlayResY: 1080"));
        assertTrue(ass.contains(
                "Style: Main,Noto Sans,54,&H00FFFFFF,&H000000FF,&H80000000,&H80000000,-1,0,0,0,100,100,0,0,3,10,0,8,"));
        assertTrue(ass.contains("Style: Second,Noto Sans,43,"));
        assertTrue(ass.contains("Dialogue: 0,0:00:01.50,0:00:03.25,Main,,0,0,0,,Hi ｛there｝\\N{\\rSecond}Salut\n"), ass);
    }

    @Test
    void classicAndYellowAreOutlinedAtTheBottom() {
        String ass =
                CaptionRules.toAss(List.of(new CaptionRules.Cue(0, 1000, "A", null)), "YELLOW", "BOTTOM", 6, 1280, 720);
        assertTrue(ass.contains(
                "Style: Main,Noto Sans,43,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,0,2,"));
        assertTrue(ass.contains(",Main,,0,0,0,,A\n"));
    }

    @Test
    void escapesOverrideCharactersAndKeepsLineBreaks() {
        assertEquals("a＼b ｛x｝\\Nc", CaptionRules.escape(" a\\b {x}\nc "));
        assertEquals("1:02:03.45", CaptionRules.time(3_723_450));
        assertEquals("0:00:00.00", CaptionRules.time(-5));
    }

    @Test
    void captionsAreValidatedAndCountAsContent() {
        OverlayRules.Captions ok = new OverlayRules.Captions(1L, 2L, "CLASSIC", "BOTTOM", 5);
        assertNull(OverlayRules.validate(new OverlayRules.Spec(List.of(), ok), 10_000L));
        assertNotNull(OverlayRules.validate(new OverlayRules.Spec(List.of()), 10_000L));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(null, null, null, null, 5)));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(1L, 1L, null, null, 5)));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(1L, null, "NEON", null, 5)));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(1L, null, null, "MIDDLE", 5)));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(1L, null, null, null, 20)));
        assertEquals("two-line subtitles", OverlayRules.describe(new OverlayRules.Spec(List.of(), ok)));
    }
}
