package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.pipeline.AudioEditRules.Inputs;
import com.example.videolingo.pipeline.AudioEditRules.Music;
import com.example.videolingo.pipeline.AudioEditRules.Spec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

// Extra sound tracks besides the music, and re-timing only the picture (keepSoundSpeed).
class AudioTracksRulesTest {

    private static final Inputs STEREO_10S = new Inputs(10_000, true, false, 10_000L);

    private static Music bed(String name, boolean duck) {
        return new Music("audio-uploads/" + name + ".mp3", 0.5, false, duck, 0);
    }

    private static Spec spec(Music music, List<Music> tracks, double speed, boolean keepSoundSpeed) {
        return new Spec(
                null,
                List.of(),
                List.of(),
                1,
                0,
                0,
                false,
                "OFF",
                false,
                speed,
                0,
                0,
                "KEEP",
                music,
                tracks,
                keepSoundSpeed);
    }

    @Test
    void extraTracksAreAChangeAndCheckedLikeTheMusic() {
        assertNull(AudioEditRules.validate(spec(null, List.of(bed("fx", false)), 1, false), 10_000L));
        assertEquals(
                "Sound track 1 must be a file uploaded here",
                AudioEditRules.validate(
                        spec(null, List.of(new Music("videos/x.mp4", 1, false, false, 0)), 1, false), 10_000L));
        assertEquals(
                "Sound track 2: volume must be between 0% and 200%",
                AudioEditRules.validate(
                        spec(
                                null,
                                List.of(bed("a", false), new Music("audio-uploads/b.mp3", 3, false, false, 0)),
                                1,
                                false),
                        10_000L));
        assertEquals(
                "Sound track 1 must start within the video",
                AudioEditRules.validate(
                        spec(null, List.of(new Music("audio-uploads/a.mp3", 1, false, false, 12_000)), 1, false),
                        10_000L));
    }

    @Test
    void atMostFourExtraTracks() {
        List<Music> five = List.of(bed("a", false), bed("b", false), bed("c", false), bed("d", false), bed("e", false));
        assertEquals("At most 4 extra sound tracks", AudioEditRules.validate(spec(null, five, 1, false), 10_000L));
        assertNull(AudioEditRules.validate(spec(null, five.subList(0, 4), 1, false), 10_000L));
    }

    @Test
    void theMusicAndTracksAreReadInOrderAndMixedTogether() {
        Spec s = spec(bed("music", false), List.of(bed("fx", false), bed("bed", false)), 1, false);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("[1:a]aformat"), g);
        assertTrue(g.contains("[2:a]aformat"), g);
        assertTrue(g.contains("[3:a]aformat"), g);
        assertTrue(g.contains("[main][music][bed1][bed2]amix=inputs=4:duration=first:normalize=0[withmusic]"), g);
        assertFalse(g.contains("asplit"), g);
    }

    @Test
    void eachDuckedBedListensToItsOwnCopyOfTheVoice() {
        Spec s = spec(bed("music", true), List.of(bed("fx", false), bed("bed", true)), 1, false);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("[main]asplit=3[voice][key0][key1]"), g);
        assertTrue(g.contains("[music][key0]sidechaincompress"), g);
        assertTrue(g.contains("[bed2][key1]sidechaincompress"), g);
        assertTrue(g.contains("[voice][ducked][bed1][ducked2]amix=inputs=4"), g);
    }

    @Test
    void withAReplacementTheBedsComeAfterIt() {
        Spec s = new Spec(
                "audio-uploads/new.mp3",
                List.of(),
                List.of(),
                1,
                0,
                0,
                false,
                "OFF",
                false,
                1,
                0,
                0,
                "KEEP",
                null,
                List.of(bed("fx", false)),
                false);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("[1:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[src]"), g);
        assertTrue(g.contains("[2:a]aformat"), g);
    }

    @Test
    void keepingTheSoundSpeedRetimesOnlyThePicture() {
        // Half speed: the picture is twice as long; the sound plays at 1× over a mix as long as the result.
        AudioEditRules.Graph slow = AudioEditRules.build(spec(null, List.of(), 0.5, true), STEREO_10S);
        assertTrue(slow.picture());
        assertTrue(slow.filter().contains("[0:v]setpts=PTS/0.5[vout]"), slow.filter());
        assertFalse(slow.filter().contains("atempo"), slow.filter());
        assertTrue(slow.filter().contains("atrim=end=20[base]"), slow.filter());
        // Without it, the sound slows with the picture over the video's own length.
        AudioEditRules.Graph both = AudioEditRules.build(spec(null, List.of(), 0.5, false), STEREO_10S);
        assertTrue(both.filter().contains("atempo=0.5"), both.filter());
        assertTrue(both.filter().contains("atrim=end=10[base]"), both.filter());
    }

    @Test
    void keepingTheSoundSpeedAtDoubleSpeedCutsTheSoundAtTheNewEnd() {
        String g =
                AudioEditRules.build(spec(null, List.of(), 2, true), STEREO_10S).filter();
        assertTrue(g.contains("atrim=end=5[base]"), g);
        assertFalse(g.contains("atempo"), g);
    }

    @Test
    void theDescriptionMentionsTracksAndPictureOnlySpeed() {
        String d = AudioEditRules.describe(spec(null, List.of(bed("a", false), bed("b", false)), 0.5, true));
        assertTrue(d.contains("2 sound tracks"), d);
        assertTrue(d.contains("0.5× speed (picture only)"), d);
    }

    @Test
    void anOldJobWithoutTheNewFieldsStillReads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String old = """
                {"replaceKey":null,"clips":[],"mutes":[],"volume":0.5,"fadeInMs":0,"fadeOutMs":0,"normalize":false,
                 "denoise":"OFF","enhanceVoice":false,"speed":1.0,"pitchSemitones":0,"balance":0,"channels":"KEEP",
                 "music":null}
                """;
        Spec s = mapper.readValue(old, Spec.class);
        assertNotNull(s.tracks());
        assertTrue(s.tracks().isEmpty());
        assertFalse(s.keepSoundSpeed());
        Spec withTracks = spec(bed("m", true), List.of(bed("fx", false)), 0.75, true);
        assertEquals(withTracks, mapper.readValue(mapper.writeValueAsString(withTracks), Spec.class));
    }
}
