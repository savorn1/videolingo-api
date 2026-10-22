package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

// A look of one's own for burned-in subtitles, over the CLASSIC / BOX / YELLOW styles.
class CaptionLookTest {

    private static final List<CaptionRules.Cue> CUES = List.of(new CaptionRules.Cue(0, 2000, "Hello", "សួស្តី"));

    @Test
    void coloursBecomeAssColoursBackwardsWithTheirTransparencyFirst() {
        assertEquals("&H00563412", CaptionRules.assColour("#123456", 1));
        assertEquals("&H80000000", CaptionRules.assColour("#000000", 0.5));
        assertEquals("&HFFFFFFFF", CaptionRules.assColour("#ffffff", 0));
    }

    @Test
    void theLookSetsColoursTheBoxAndBold() {
        CaptionRules.Look look = new CaptionRules.Look("#ffd60a", "#ffffff", "#1e3a8a", true, 0.75, false);
        String ass = CaptionRules.toAss(CUES, "CLASSIC", "BOTTOM", 5, 1280, 720, look);
        // Main: yellow text, a 75 % solid blue box (BorderStyle 3), not bold.
        assertTrue(ass.contains("Style: Main,Noto Sans,36,&H000AD6FF,&H000000FF,&H408A3A1E,&H80000000,0,"), ass);
        assertTrue(ass.contains(",3,"), ass);
        assertTrue(ass.contains("Style: Second,Noto Sans,29,&H00FFFFFF,"), ass);
    }

    @Test
    void withoutALookTheStyleIsAsBefore() {
        String ass = CaptionRules.toAss(CUES, "YELLOW", "BOTTOM", 5, 1280, 720);
        assertTrue(ass.contains("Style: Main,Noto Sans,36,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,"), ass);
    }

    @Test
    void theLookIsChecked() {
        assertNull(CaptionRules.validateLook(null));
        assertNull(CaptionRules.validateLook(new CaptionRules.Look("#ffffff", "#ffd60a", "#000000", false, 0.5, true)));
        assertNotNull(
                CaptionRules.validateLook(new CaptionRules.Look("white", "#ffd60a", "#000000", false, 0.5, true)));
        assertNotNull(CaptionRules.validateLook(new CaptionRules.Look("#ffffff", "#ffd60a", "#000000", true, 2, true)));
        assertNotNull(OverlayRules.validateCaptions(new OverlayRules.Captions(
                1L, null, "CLASSIC", "BOTTOM", 5, new CaptionRules.Look("#ffffff", null, "#000000", false, 1, true))));
    }
}
