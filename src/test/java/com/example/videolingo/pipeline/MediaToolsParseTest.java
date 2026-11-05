package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MediaToolsParseTest {

    @Test
    void readsTheLatestProgressPosition() {
        assertNull(MediaTools.lastOutTimeUs("frame=0\nout_time_us=N/A\nprogress=continue\n"));
        assertEquals(
                2_500_000L,
                MediaTools.lastOutTimeUs(
                        "out_time_us=1000000\nprogress=continue\nout_time_us=2500000\nprogress=continue\n"));
        assertEquals(42L, MediaTools.lastOutTimeUs("out_time_ms=42\n"));
    }

    @Test
    void probeReadsDurationStreamsAndFrameSize() {
        String text = """
                  Duration: 00:01:02.50, start: 0.000000, bitrate: 902 kb/s
                  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive), 1280x720 [SAR 1:1 DAR 16:9], 25 fps
                  Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz, mono, fltp, 69 kb/s (default)
                """;
        MediaTools.Probe p = MediaTools.parseProbe(text);
        assertEquals(62_500L, p.durationMs());
        assertTrue(p.hasVideo() && p.hasAudio() && p.mono());
        assertEquals(1280, p.width());
        assertEquals(720, p.height());

        MediaTools.Probe song = MediaTools.parseProbe("""
                  Duration: 00:00:05.00, start: 0.000000
                  Stream #0:0: Audio: mp3 (mp3float), 44100 Hz, stereo, fltp, 128 kb/s
                  Stream #0:1: Video: png, rgb24(pc), 500x500, 90k tbr (attached pic)
                """);
        assertFalse(song.hasVideo());
        assertFalse(song.mono());
        assertNull(song.width());
    }

    @Test
    void downsampleKeepsTheLoudestInEachSlice() {
        assertArrayEquals(new float[] {0.5f, 0.9f}, MediaTools.downsample(List.of(0.1f, 0.5f, 0.9f, 0.2f), 2));
        assertArrayEquals(new float[] {0.3f}, MediaTools.downsample(List.of(0.3f), 10));
        assertEquals(0, MediaTools.downsample(List.of(), 5).length);
    }

    @Test
    void downloadProblemSaysWhyYtDlpGaveNoVideo() {
        String tooLong =
                "[youtube] abc: Downloading webpage\n[download] Talk does not pass filter (duration < 10800), skipping ..\n";
        assertEquals(
                "The video is 4 h 12 min long, longer than the 180-minute limit. Raise PIPELINE_MAX_MEDIA_MINUTES on the"
                        + " server, or cut it shorter and upload the file.",
                MediaTools.downloadProblem(tooLong, 2048L, 180, 15_120L));
        assertTrue(MediaTools.downloadProblem(tooLong, 2048L, 180, null)
                .startsWith("The video is longer than the 180-minute"));

        String tooBig =
                "\r[download] File is larger than max-filesize (3000000000 bytes > 2147483648 bytes). Aborting.";
        assertTrue(MediaTools.downloadProblem(tooBig, 2048L, 180, null)
                .startsWith("The video is larger than the 2048 MB limit"));

        assertTrue(MediaTools.downloadProblem(
                        "ERROR: [youtube] abc: Private video. Sign in if you've been granted access", 2048L, 180, null)
                .startsWith("The video is private"));
        assertTrue(MediaTools.downloadProblem(
                        "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate",
                        null,
                        180,
                        null)
                .startsWith("The video is age-restricted"));
        assertTrue(
                MediaTools.downloadProblem("ERROR: [youtube] abc: Sign in to confirm you’re not a bot", null, 180, null)
                        .contains("blocking downloads"));
        assertTrue(MediaTools.downloadProblem(
                        "ERROR: [youtube] abc: Video unavailable. This video has been removed", null, 180, null)
                .contains("isn't on the site any more"));
        assertTrue(MediaTools.downloadProblem("ERROR: Unsupported URL: https://example.com/", null, 180, null)
                .contains("isn't a video page"));
    }

    @Test
    void downloadProblemReadsOnlyErrorLinesForTheSitesReasons() {
        // A title that happens to say "private video" isn't a reason.
        assertNull(MediaTools.downloadProblem("[download] Destination: My private video tips\n", null, 180, null));
        assertNull(MediaTools.downloadProblem("", 2048L, 180, null));
    }

    @Test
    void readsTheDownloadsLength() {
        assertEquals(1235L, MediaTools.parseSeconds("1234.6\n"));
        assertNull(MediaTools.parseSeconds("NA\n"));
        assertNull(MediaTools.parseSeconds(""));
        assertEquals("4 h 12 min", MediaTools.hoursMinutes(15_120));
        assertEquals("3 h", MediaTools.hoursMinutes(10_800));
        assertEquals("52 min", MediaTools.hoursMinutes(3_120));
    }
}
