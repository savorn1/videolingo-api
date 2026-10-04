package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.pipeline.AudioEditRules.Clip;
import com.example.videolingo.pipeline.AudioEditRules.Inputs;
import com.example.videolingo.pipeline.AudioEditRules.Music;
import com.example.videolingo.pipeline.AudioEditRules.Range;
import com.example.videolingo.pipeline.AudioEditRules.Spec;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioEditRulesTest {

    private static final Inputs STEREO_10S = new Inputs(10_000, true, false, 10_000L);

    private static Spec base() {
        return new Spec(null, List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null);
    }

    private static Spec volume(double v) {
        return new Spec(null, List.of(), List.of(), v, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null);
    }

    @Test
    void anUnchangedEditIsRejected() {
        assertTrue(base().isNoop());
        assertNotNull(AudioEditRules.validate(base(), 10_000L));
        assertNull(AudioEditRules.validate(volume(0.5), 10_000L));
    }

    @Test
    void uploadedFilesMustComeFromTheAudioUploadFolder() {
        Spec replace =
                new Spec("videos/other.mp4", List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null);
        assertNotNull(AudioEditRules.validate(replace, null));
        Spec sneaky = new Spec(
                "audio-uploads/../videos/x.mp4",
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
                null);
        assertNotNull(AudioEditRules.validate(sneaky, null));
        Spec ok = new Spec(
                "audio-uploads/a.mp3", List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null);
        assertNull(AudioEditRules.validate(ok, null));
        Spec music = new Spec(
                null,
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
                new Music("thumbnails/x.png", 0.3, true, true, 0));
        assertNotNull(AudioEditRules.validate(music, null));
    }

    @Test
    void rangesAndLevelsAreBounded() {
        Spec backwards = new Spec(
                null,
                List.of(new Clip(5000, 4000, 0, 1)),
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
                null);
        assertNotNull(AudioEditRules.validate(backwards, null));
        Spec late = new Spec(
                null,
                List.of(new Clip(0, 1000, 12_000, 1)),
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
                null);
        assertNotNull(AudioEditRules.validate(late, 10_000L));
        assertNotNull(AudioEditRules.validate(volume(5), null));
        Spec fast = new Spec(null, List.of(), List.of(), 1, 0, 0, false, "OFF", false, 3, 0, 0, "KEEP", null);
        assertNotNull(AudioEditRules.validate(fast, null));
        Spec pitch = new Spec(null, List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 13, 0, "KEEP", null);
        assertNotNull(AudioEditRules.validate(pitch, null));
        Spec fades = new Spec(null, List.of(), List.of(), 1, 6000, 6000, false, "OFF", false, 1, 0, 0, "KEEP", null);
        assertNotNull(AudioEditRules.validate(fades, 10_000L));
        Spec mute = new Spec(
                null, List.of(), List.of(new Range(3000, 3000)), 1, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null);
        assertNotNull(AudioEditRules.validate(mute, null));
    }

    @Test
    void clipsAreCutPlacedAndMixed() {
        Spec s = new Spec(
                null,
                List.of(new Clip(0, 2000, 0, 1), new Clip(5000, 7000, 2000, 0.5)),
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
                null);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("[src]asplit=2[s0][s1]"), g);
        assertTrue(g.contains("[s0]atrim=start=0:end=2,asetpts=PTS-STARTPTS[c0]"), g);
        assertTrue(
                g.contains("[s1]atrim=start=5:end=7,asetpts=PTS-STARTPTS,volume=0.5,adelay=delays=2000:all=1[c1]"), g);
        assertTrue(g.contains("anullsrc=r=48000:cl=stereo,atrim=end=10[base]"), g);
        assertTrue(g.contains("[base][c0][c1]amix=inputs=3:duration=first:normalize=0[placed]"), g);
    }

    @Test
    void effectsAreChainedInOrder() {
        Spec s = new Spec(
                null,
                List.of(),
                List.of(new Range(1000, 2500)),
                1.5,
                500,
                1000,
                true,
                "LIGHT",
                true,
                1,
                0,
                -0.5,
                "MONO",
                null);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("[base][src]amix=inputs=2:duration=first:normalize=0[placed]"), g);
        assertTrue(
                g.contains("[placed]volume=0:enable='between(t,1,2.5)',volume=1.5,afftdn=nr=12:nf=-40,highpass=f=80"),
                g);
        assertTrue(g.contains("[main]pan=stereo|c0=1*c0|c1=0.5*c1,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000"), g);
        assertTrue(g.contains("afade=t=in:st=0:d=0.5,afade=t=out:st=9:d=1,pan=mono|c0=0.5*c0+0.5*c1[aout]"), g);
        assertFalse(AudioEditRules.build(s, STEREO_10S).picture());
    }

    @Test
    void speedChangesThePictureTooAndFadesFollowTheNewLength() {
        Spec s = new Spec(null, List.of(), List.of(), 1, 0, 1000, false, "OFF", false, 2, 0, 0, "KEEP", null);
        AudioEditRules.Graph g = AudioEditRules.build(s, STEREO_10S);
        assertTrue(g.picture());
        assertTrue(g.filter().contains("[0:v]setpts=PTS/2[vout]"), g.filter());
        assertTrue(g.filter().contains("atempo=2,afade=t=out:st=4:d=1"), g.filter());
    }

    @Test
    void pitchResamplesAndRestoresTheTempo() {
        Spec s = new Spec(null, List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 12, 0, "KEEP", null);
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.contains("asetrate=96000,aresample=48000,atempo=0.5"), g);
    }

    @Test
    void atempoIsChainedOutsideItsRange() {
        assertEquals(List.of(), AudioEditRules.atempo(1));
        assertEquals(List.of("atempo=1.5"), AudioEditRules.atempo(1.5));
        assertEquals(List.of("atempo=2", "atempo=2"), AudioEditRules.atempo(4));
        assertEquals(List.of("atempo=0.5", "atempo=0.5"), AudioEditRules.atempo(0.25));
    }

    @Test
    void musicIsMixedUnderAndDucked() {
        Spec s = new Spec(
                "audio-uploads/voice.mp3",
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
                new Music("audio-uploads/song.mp3", 0.3, true, true, 1500));
        String g = AudioEditRules.build(s, STEREO_10S).filter();
        assertTrue(g.startsWith("[1:a]aformat"), g);
        assertTrue(
                g.contains(
                        "[2:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,volume=0.3,adelay=delays=1500:all=1[music]"),
                g);
        assertTrue(g.contains("[music][key]sidechaincompress"), g);
        assertTrue(g.contains("[voice][ducked]amix=inputs=2:duration=first:normalize=0[withmusic]"), g);
    }

    @Test
    void aVideoWithoutSoundStartsFromSilence() {
        Spec s = volume(0.5);
        String g = AudioEditRules.build(s, new Inputs(8000, false, false, null)).filter();
        assertTrue(g.startsWith("anullsrc=r=48000:cl=stereo,atrim=end=8[silence];[silence]aformat"), g);
    }

    @Test
    void describesTheChangesInALine() {
        Spec s = new Spec(
                null,
                List.of(new Clip(0, 1000, 0, 1), new Clip(2000, 3000, 1000, 1)),
                List.of(),
                1.5,
                0,
                2000,
                true,
                "STRONG",
                false,
                1.25,
                -2,
                0,
                "MONO",
                new Music("audio-uploads/m.mp3", 0.2, true, true, 0));
        assertEquals(
                "2 clips, volume 150%, fade out 2 s, normalized, strong noise reduction, background music 20% (ducked), pitch -2 st, "
                        + "1.25× speed, mono",
                AudioEditRules.describe(s));
    }

    @Test
    void keepChannelsFollowsAMonoSource() {
        String g = AudioEditRules.build(volume(0.8), new Inputs(10_000, true, true, 10_000L))
                .filter();
        assertTrue(g.endsWith("pan=mono|c0=0.5*c0+0.5*c1[aout]"), g);
    }
}
