package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class TextRendererTest {

    static OverlayRules.Layer layer(String text, String align, String background) {
        return new OverlayRules.Layer(
                "TEXT",
                text,
                "SansSerif",
                400,
                10,
                "#ff0000",
                background,
                1,
                align,
                null,
                0,
                0.5,
                0.5,
                1,
                0,
                null,
                "NONE");
    }

    @Test
    void sizeFollowsTheVideoHeightAndLineCount() {
        BufferedImage one = TextRenderer.render(layer("Hello", "CENTER", null), 720);
        BufferedImage two = TextRenderer.render(layer("Hello\nWorld", "CENTER", null), 720);
        BufferedImage big = TextRenderer.render(layer("Hello", "CENTER", null), 1440);
        assertTrue(two.getHeight() > one.getHeight() * 1.6, one.getHeight() + " vs " + two.getHeight());
        assertTrue(big.getWidth() > one.getWidth() * 1.8, one.getWidth() + " vs " + big.getWidth());
    }

    @Test
    void transparentWithoutABackgroundOpaqueBoxWithOne() {
        BufferedImage plain = TextRenderer.render(layer("Hi", "CENTER", null), 720);
        BufferedImage boxed = TextRenderer.render(layer("Hi", "CENTER", "#0000ff"), 720);
        assertEquals(0, plain.getRGB(1, plain.getHeight() / 2) >>> 24);
        int mid = boxed.getRGB(3, boxed.getHeight() / 2);
        assertEquals(255, mid >>> 24);
        assertEquals(0x0000ff, mid & 0xffffff);
    }

    @Test
    void alignmentPlacesShortLinesLeftOrRight() {
        String text = "A much longer first line\nx";
        BufferedImage left = TextRenderer.render(layer(text, "LEFT", null), 720);
        BufferedImage right = TextRenderer.render(layer(text, "RIGHT", null), 720);
        // The short second line: ink near the left edge for LEFT, near the right edge for RIGHT.
        assertTrue(inkIn(left, 0, left.getWidth() / 4, left.getHeight() / 2, left.getHeight()));
        assertTrue(inkIn(right, right.getWidth() * 3 / 4, right.getWidth(), right.getHeight() / 2, right.getHeight()));
        assertTrue(!inkIn(left, left.getWidth() * 3 / 4, left.getWidth(), left.getHeight() * 3 / 4, left.getHeight()));
    }

    @Test
    void cssWeightsMapOntoJavaWeights() {
        assertTrue(TextRenderer.javaWeight(300) < TextRenderer.javaWeight(400));
        assertTrue(TextRenderer.javaWeight(700) > TextRenderer.javaWeight(500));
        assertTrue(TextRenderer.fonts().subList(0, 3).containsAll(TextRenderer.LOGICAL));
    }

    private static boolean inkIn(BufferedImage img, int x0, int x1, int y0, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if ((img.getRGB(x, y) >>> 24) > 128) {
                    return true;
                }
            }
        }
        return false;
    }
}
