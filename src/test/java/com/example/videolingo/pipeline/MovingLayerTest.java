package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.example.videolingo.pipeline.OverlayRules.Layer;
import com.example.videolingo.pipeline.OverlayRules.Spec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// A layer that moves and/or changes size over its time on screen (toX, toY, toScale).
class MovingLayerTest {

    private static Layer image(Double toX, Double toY, Double toScale) {
        return new Layer(
                "IMAGE",
                null,
                null,
                400,
                5,
                "#ffffff",
                null,
                0,
                "CENTER",
                "overlay-uploads/a.png",
                20,
                0.2,
                0.2,
                1,
                1000,
                3000L,
                "NONE",
                toX,
                toY,
                toScale);
    }

    @Test
    void aStillLayerIsDrawnAsBefore() {
        Layer still = image(null, null, null);
        assertFalse(still.moving());
        String g =
                OverlayRules.build(new Spec(List.of(still), null), 1280, 4000).filter();
        assertTrue(g.contains("scale=w=256:h=-1"), g);
        assertTrue(g.contains("overlay=x='main_w*0.2-overlay_w/2':y='main_h*0.2-overlay_h/2'"), g);
    }

    @Test
    void aMovingLayerGoesFromItsPlaceToTheNewOneAcrossItsTime() {
        String g = OverlayRules.build(new Spec(List.of(image(0.8, 0.6, 2.0)), null), 1280, 4000)
                .filter();
        String along = "min(1,max(0,(t-1)/2))";
        assertTrue(g.contains("scale=w='max(2,trunc(256*(1+1*" + along + ")/2)*2)':h=-1:eval=frame"), g);
        assertTrue(g.contains("x='main_w*(0.2+0.6*" + along + ")-overlay_w/2'"), g);
        assertTrue(g.contains("y='main_h*(0.2+0.4*" + along + ")-overlay_h/2'"), g);
    }

    @Test
    void whereItGoesMustBeInsideAndItsSizeSensible() {
        assertNull(OverlayRules.validate(new Spec(List.of(image(0.8, 0.6, 2.0)), null), 4000L));
        assertNotNull(OverlayRules.validate(new Spec(List.of(image(1.5, null, null)), null), 4000L));
        assertNotNull(OverlayRules.validate(new Spec(List.of(image(null, null, 10.0)), null), 4000L));
    }

    @Test
    void anOldLayerWithoutTheNewFieldsReads() throws Exception {
        String json = """
                {"kind":"TEXT","text":"Hi","weight":700,"sizePct":6,"color":"#ffffff","align":"CENTER",
                 "widthPct":0,"x":0.5,"y":0.5,"opacity":1,"startMs":0,"animation":"NONE"}
                """;
        Layer l = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Layer.class);
        assertFalse(l.moving());
        assertEquals(1, l.endScale());
    }

    /** Real ffmpeg: a picture moving across and growing renders a clip of the same length. */
    @Test
    void itRenders(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        Path video = dir.resolve("v.mp4");
        Path png = dir.resolve("a.png");
        assertEquals(
                0,
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc=size=320x180:rate=25:duration=4",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        video.toString())));
        assertEquals(
                0,
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=c=red:s=64x64",
                        "-frames:v",
                        "1",
                        png.toString())));
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        OverlayRules.Graph graph = OverlayRules.build(new Spec(List.of(image(0.8, 0.7, 2.0)), null), 320, 4000);
        Path out = media.overlay(
                video.toString(), List.of(png), graph, 4000, new JobContext(mock(JobStore.class), 1, dir));
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(Math.abs(p.durationMs() - 4000) < 400, "got " + p.durationMs());
    }

    private static int run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }
}
