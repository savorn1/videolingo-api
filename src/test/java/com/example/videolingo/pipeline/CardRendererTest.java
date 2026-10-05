package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class CardRendererTest {

    private static BufferedImage img(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    @Test
    void theLogoGoesAboveTheTextWithAGap() {
        BufferedImage out = CardRenderer.compose(img(400, 60), img(200, 100), 1280, 720);
        assertEquals(400, out.getWidth());
        assertEquals(60 + 100 + 720 / 30, out.getHeight());
    }

    @Test
    void aBigLogoIsShrunkToAQuarterOfTheWidthAndNeverEnlarged() {
        BufferedImage big = CardRenderer.scaleToFit(img(2000, 1000), 320, 240);
        assertEquals(320, big.getWidth());
        assertEquals(160, big.getHeight());
        BufferedImage small = img(50, 20);
        assertSame(small, CardRenderer.scaleToFit(small, 320, 240));
    }

    @Test
    void eitherPartMayBeMissing() {
        assertEquals(60, CardRenderer.compose(img(400, 60), null, 1280, 720).getHeight());
        assertTrue(CardRenderer.compose(null, img(100, 50), 1280, 720).getWidth() > 0);
        assertEquals("CENTER", CardRenderer.textLayer(" Hi ", "#ffffff").align());
        assertEquals("Hi", CardRenderer.textLayer(" Hi ", "#ffffff").text());
    }
}
