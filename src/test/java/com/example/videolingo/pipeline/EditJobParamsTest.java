package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoClip;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

// What an edit job stores must read back as the same thing — including jobs queued by an older build.
class EditJobParamsTest {

    private final ObjectMapper writer = new ObjectMapper();
    private final ObjectMapper reader = EditJobParams.reader(writer);

    @Test
    void aFullTrimSurvivesTheRoundTrip() throws Exception {
        EditJobParams.Trim trim = new EditJobParams.Trim(
                "TRIM",
                1500,
                9000L,
                new MediaTools.CropRect(0, 0, 640, 360),
                new MediaTools.ScaleSize(320, 180),
                90,
                true,
                null,
                null,
                2000L,
                new VideoEditRules.Look(0.1, 1.2, 1, 0, true, false, false, -0.9, false),
                "ZOOM_IN",
                new VideoEditRules.Fade(500, 500, "WHITE"),
                new VideoEditRules.Freeze(2000, 1500),
                java.util.List.of(new VideoEditRules.BlurBox(10, 10, 100, 50)),
                new VideoEditRules.SpeedRange(1000, 3000, 0.5),
                new VideoEditRules.Pip(7L, "TOP_LEFT", 30),
                new VideoEditRules.Cards(new VideoEditRules.Card("Hello", 3000), null, "#1e3a8a", "#ffffff", null),
                true,
                true,
                new VideoEditRules.ZoomTo(0.3, 0.6, 0.5),
                new VideoEditRules.GreenScreen(null, 0.3, "#112233"),
                new ReframeRules.Reframe(0.5625),
                java.util.List.of(new VideoEditRules.Freeze(3000, 1000)),
                java.util.List.of(new VideoEditRules.SpeedRange(5000, 6000, 2.0)),
                new VideoEditRules.PunchIn(1.12, java.util.List.of(new VideoEditRules.Segment(500, 1500L))),
                "#ffcc00",
                "luts/0b0c0d0e-0000-4000-8000-000000000000.cube",
                null);
        String json = writer.writeValueAsString(trim);
        assertFalse(json.contains("flipV"), "unused settings are left out");
        assertFalse(json.contains("plain") || json.contains("\"none\""), "derived flags aren't stored");
        assertEquals(trim, reader.readValue(json, EditJobParams.Trim.class));
    }

    @Test
    void aTrimQueuedBeforeTheRecordsStillRuns() throws Exception {
        // As the old hand-built map wrote it, with the derived flag that broke the look.
        String json = """
                {"operation":"TRIM","startMs":0,"endMs":237000,"crop":{"x":0,"y":0,"w":1280,"h":720},
                 "rotate":90,"flipH":true,"padMs":3000,
                 "look":{"brightness":0,"contrast":1.1,"saturation":1,"blur":0,"grayscale":true,"sepia":false,
                         "vignette":false,"warmth":-0.9,"sharpen":false,"plain":false}}
                """;
        EditJobParams.Trim t = reader.readValue(json, EditJobParams.Trim.class);
        assertEquals(237000L, t.endMs());
        assertEquals(new MediaTools.CropRect(0, 0, 1280, 720), t.crop());
        assertEquals(90, t.rotate());
        assertTrue(t.flipH());
        assertNull(t.flipV());
        assertEquals(3000L, t.padMs());
        assertEquals(-0.9, t.look().warmth());
        assertNull(t.fade());
    }

    @Test
    void fieldsFromANewerBuildAreSkipped() throws Exception {
        EditJobParams.Cut cut = reader.readValue(
                "{\"operation\":\"CUT\",\"cuts\":[{\"startMs\":1000,\"endMs\":null,\"label\":\"x\"}],\"v\":2}",
                EditJobParams.Cut.class);
        assertEquals(List.of(new EditJobParams.Range(1000, null)), cut.cuts());
    }

    @Test
    void aResultMadeFromAnOlderFileIsStale() {
        Video video = Video.builder().storageKey("videos/b.mp4").build();
        assertTrue(VideoEditService.isStale(
                VideoClip.builder().sourceKey("videos/a.mp4").build(), video));
        assertFalse(VideoEditService.isStale(
                VideoClip.builder().sourceKey("videos/b.mp4").build(), video));
        // Made before the source was recorded: can't tell, so it isn't blocked.
        assertFalse(VideoEditService.isStale(VideoClip.builder().build(), video));
    }

    @Test
    void theExactLengthWinsOverTheRoundedOne() {
        assertEquals(
                10_400L,
                PipelineSteps.durationMs(
                        Video.builder().durationSeconds(10).durationMs(10_400L).build()));
        assertEquals(
                10_000L,
                PipelineSteps.durationMs(Video.builder().durationSeconds(10).build()));
        assertNull(PipelineSteps.durationMs(Video.builder().build()));
        assertEquals(10, PipelineSteps.seconds(10_400L));
        assertEquals(11, PipelineSteps.seconds(10_600L));
        assertEquals(1, PipelineSteps.seconds(300L));
    }

    @Test
    void cachedFilesKeepTheirExtensionButNotTheKey() {
        String name = SourceFileCache.fileName("edits/uploads/../my clip.MP4");
        assertTrue(name.endsWith(".MP4"));
        assertFalse(name.contains("/") || name.contains(" "));
        assertEquals(name, SourceFileCache.fileName("edits/uploads/../my clip.MP4"));
    }

    @Test
    void aSplitQueuedBeforePartsHadNamesStillRuns() throws Exception {
        String json = """
                {"operation":"SPLIT","segments":[{"startMs":0,"endMs":5000},{"startMs":5000,"endMs":null}]}
                """;
        EditJobParams.Split split = reader.readValue(json, EditJobParams.Split.class);
        assertEquals(new EditJobParams.SplitPart(0, 5000L, null, null), split.segments().get(0));
        assertEquals(new EditJobParams.SplitPart(5000, null, null, null), split.segments().get(1));
    }

    @Test
    void aNamedShapedSplitSurvivesTheRoundTrip() throws Exception {
        EditJobParams.Split split = new EditJobParams.Split(
                "SPLIT", List.of(new EditJobParams.SplitPart(0, 5000L, "Greetings", 0.5625), new EditJobParams.SplitPart(5000, null, null, null)));
        assertEquals(split, reader.readValue(writer.writeValueAsString(split), EditJobParams.Split.class));
    }
}
