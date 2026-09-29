package com.example.videolingo.pipeline;

import com.example.videolingo.pipeline.OverlayRules.Layer;
import com.example.videolingo.pipeline.OverlayRules.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayRulesTest {

    static Layer text(String t, String animation, double opacity) {
        return new Layer("TEXT", t, "SansSerif", 700, 6, "#ffffff", "#000000", 0.5, "CENTER", null, 0, 0.5, 0.85, opacity, 1000, 4000L, animation);
    }

    static Layer image(String key) {
        return new Layer("IMAGE", null, null, 400, 0, null, null, 0, null, key, 15, 0.9, 0.1, 0.5, 0, null, "NONE");
    }

    @Test
    void validLayersPass() {
        assertNull(OverlayRules.validate(new Spec(List.of(text("Hello", "FADE", 1), image("overlay-uploads/logo.png"))), 10_000L));
    }

    @Test
    void badLayersAreRejectedWithTheirNumber() {
        assertNotNull(OverlayRules.validate(new Spec(List.of()), null));
        assertTrue(OverlayRules.validate(new Spec(List.of(text("ok", "NONE", 1), text(" ", "NONE", 1))), null).startsWith("Layer 2"));
        assertNotNull(OverlayRules.validate(new Spec(List.of(image("thumbnails/x.png"))), null));
        assertNotNull(OverlayRules.validate(new Spec(List.of(image("overlay-uploads/../videos/x.mp4"))), null));
        assertNotNull(OverlayRules.validate(new Spec(List.of(text("x", "SPIN", 1))), null));
        assertNotNull(OverlayRules.validate(new Spec(List.of(text("x", "NONE", 0))), null));
        Layer late = new Layer("TEXT", "x", "SansSerif", 400, 5, "#fff000", null, 0, "LEFT", null, 0, 0.5, 0.5, 1, 20_000, null, "NONE");
        assertNotNull(OverlayRules.validate(new Spec(List.of(late)), 10_000L));
        Layer badColour = new Layer("TEXT", "x", "SansSerif", 400, 5, "white", null, 0, "LEFT", null, 0, 0.5, 0.5, 1, 0, null, "NONE");
        assertNotNull(OverlayRules.validate(new Spec(List.of(badColour)), null));
    }

    @Test
    void describesTheLayers() {
        assertEquals("Text “Hello”, 1 image", OverlayRules.describe(new Spec(List.of(text("Hello", "NONE", 1), image("overlay-uploads/a.png")))));
        assertEquals("2 text layers", OverlayRules.describe(new Spec(List.of(text("a", "NONE", 1), text("b", "NONE", 1)))));
    }

    @Test
    void graphChainsOverlaysInOrderWithTimingOpacityAndFades() {
        String g = OverlayRules.build(new Spec(List.of(text("Hi", "FADE", 0.8), image("overlay-uploads/a.png"))), 1280, 10_000).filter();
        assertTrue(g.contains("[1:v]format=rgba,colorchannelmixer=aa=0.8,fade=t=in:st=1:d=0.5:alpha=1,fade=t=out:st=3.5:d=0.5:alpha=1[l0]"), g);
        assertTrue(g.contains("[0:v][l0]overlay=x='main_w*0.5-overlay_w/2':y='main_h*0.85-overlay_h/2':eval=frame:enable='between(t,1,4)'[v0]"), g);
        assertTrue(g.contains("[2:v]format=rgba,scale=w=192:h=-1,colorchannelmixer=aa=0.5[l1]"), g);
        assertTrue(g.contains("[v0][l1]overlay=x='main_w*0.9-overlay_w/2':y='main_h*0.1-overlay_h/2':eval=frame:enable='between(t,0,10)'[vout]"), g);
    }

    @Test
    void slidesMoveInFromBelowOrTheLeft() {
        String up = OverlayRules.build(new Spec(List.of(text("Hi", "SLIDE_UP", 1))), 1280, 10_000).filter();
        assertTrue(up.contains("y='main_h*0.85-overlay_h/2+main_h*0.06*max(0,1-(t-1)/0.5)'"), up);
        String left = OverlayRules.build(new Spec(List.of(text("Hi", "SLIDE_LEFT", 1))), 1280, 10_000).filter();
        assertTrue(left.contains("x='main_w*0.5-overlay_w/2-(main_w*0.5+overlay_w/2)*max(0,1-(t-1)/0.5)'"), left);
    }
}
