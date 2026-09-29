package com.example.videolingo.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// An audio edit of a video, as one ffmpeg filter graph: which sound to start
// from (the video's own, or an uploaded file), which pieces of it go where on
// the video's timeline (clips — split, moved, duplicated, deleted in the
// editor), then muted ranges, level, clean-up, background music, balance,
// pitch/speed, loudness and fades. Pure — validation and graph building only,
// so both are unit-tested without running ffmpeg.
public final class AudioEditRules {

    public static final int MAX_CLIPS = 50;
    public static final int MAX_MUTES = 50;
    public static final double MIN_SPEED = 0.5;
    public static final double MAX_SPEED = 2.0;
    public static final double MAX_PITCH = 12;
    public static final double MAX_GAIN = 4;
    public static final long MAX_FADE_MS = 60_000;
    /** Uploaded audio lives under this prefix; nothing else in the bucket may be named in a request. */
    public static final String UPLOAD_PREFIX = "audio-uploads/";
    static final int RATE = 48_000;

    public static final Set<String> DENOISE = Set.of("OFF", "LIGHT", "STRONG");
    public static final Set<String> CHANNELS = Set.of("KEEP", "MONO", "STEREO");

    /** A piece of the source sound [srcStartMs, srcEndMs), placed at atMs on the video's timeline. */
    public record Clip(long srcStartMs, long srcEndMs, long atMs, double gain) {
    }

    public record Range(long startMs, long endMs) {
    }

    public record Music(String key, double volume, boolean loop, boolean duck, long startMs) {
    }

    /**
     * A complete edit, with defaults filled in. clips empty = the source sound
     * as-is, from 0. Times are on the video's own (1×) timeline; speed then
     * scales the whole result, picture included.
     */
    public record Spec(String replaceKey, List<Clip> clips, List<Range> mutes, double volume, long fadeInMs, long fadeOutMs,
                       boolean normalize, String denoise, boolean enhanceVoice, double speed, double pitchSemitones, double balance,
                       String channels, Music music) {

        public Spec {
            clips = clips == null ? List.of() : List.copyOf(clips);
            mutes = mutes == null ? List.of() : List.copyOf(mutes);
            denoise = denoise == null ? "OFF" : denoise;
            channels = channels == null ? "KEEP" : channels;
        }

        @JsonIgnore
        public boolean changesPicture() {
            return speed != 1;
        }

        /** True when rendering it would give back the same sound. */
        @JsonIgnore
        public boolean isNoop() {
            boolean identityClips = clips.isEmpty()
                    || (clips.size() == 1 && clips.get(0).srcStartMs() == 0 && clips.get(0).atMs() == 0 && clips.get(0).gain() == 1
                    && clips.get(0).srcEndMs() == Long.MAX_VALUE);
            return replaceKey == null && identityClips && mutes.isEmpty() && volume == 1 && fadeInMs == 0 && fadeOutMs == 0
                    && !normalize && "OFF".equals(denoise) && !enhanceVoice && speed == 1 && pitchSemitones == 0 && balance == 0
                    && "KEEP".equals(channels) && music == null;
        }
    }

    /** What ffmpeg found in the inputs. sourceDurationMs: the sound clips are cut from (video or replacement). */
    public record Inputs(long videoDurationMs, boolean videoHasAudio, boolean sourceMono, Long sourceDurationMs) {
    }

    /** filter_complex, and whether the picture goes through it too ([vout]) or is copied. */
    public record Graph(String filter, boolean picture) {
    }

    private AudioEditRules() {
    }

    // ── validation ───────────────────────────────────────────────────────

    /** Null = valid; a message otherwise. durationMs null = unknown, so only what can be checked without it is. */
    public static String validate(Spec s, Long durationMs) {
        if (s.isNoop()) {
            return "Nothing to change — pick at least one audio change";
        }
        if (s.replaceKey() != null && !isUploadKey(s.replaceKey())) {
            return "The replacement audio must be a file uploaded here";
        }
        if (s.clips().size() > MAX_CLIPS) {
            return "At most " + MAX_CLIPS + " audio clips";
        }
        for (int i = 0; i < s.clips().size(); i++) {
            Clip c = s.clips().get(i);
            String where = "Audio clip " + (i + 1) + ": ";
            if (c.srcStartMs() < 0 || c.atMs() < 0) {
                return where + "times can't be negative";
            }
            if (c.srcEndMs() <= c.srcStartMs()) {
                return where + "the end must be after the start";
            }
            if (c.gain() < 0 || c.gain() > MAX_GAIN) {
                return where + "volume must be between 0% and " + (int) (MAX_GAIN * 100) + "%";
            }
            if (durationMs != null && c.atMs() >= durationMs) {
                return where + "it starts after the end of the video";
            }
        }
        if (s.mutes().size() > MAX_MUTES) {
            return "At most " + MAX_MUTES + " muted ranges";
        }
        for (int i = 0; i < s.mutes().size(); i++) {
            Range r = s.mutes().get(i);
            if (r.startMs() < 0 || r.endMs() <= r.startMs()) {
                return "Muted range " + (i + 1) + ": the end must be after the start";
            }
        }
        if (s.volume() < 0 || s.volume() > MAX_GAIN) {
            return "Volume must be between 0% and " + (int) (MAX_GAIN * 100) + "%";
        }
        if (s.fadeInMs() < 0 || s.fadeInMs() > MAX_FADE_MS || s.fadeOutMs() < 0 || s.fadeOutMs() > MAX_FADE_MS) {
            return "Fades can be at most " + (MAX_FADE_MS / 1000) + " seconds";
        }
        if (durationMs != null && s.fadeInMs() + s.fadeOutMs() > durationMs / s.speed()) {
            return "The fades are longer than the video";
        }
        if (!DENOISE.contains(s.denoise())) {
            return "Unknown noise reduction level";
        }
        if (!CHANNELS.contains(s.channels())) {
            return "Channels must be KEEP, MONO or STEREO";
        }
        if (s.speed() < MIN_SPEED || s.speed() > MAX_SPEED) {
            return "Speed must be between " + MIN_SPEED + "× and " + MAX_SPEED + "×";
        }
        if (Math.abs(s.pitchSemitones()) > MAX_PITCH) {
            return "Pitch can move at most " + (int) MAX_PITCH + " semitones";
        }
        if (s.balance() < -1 || s.balance() > 1) {
            return "Balance must be between -1 (left) and 1 (right)";
        }
        if (s.music() != null) {
            Music m = s.music();
            if (!isUploadKey(m.key())) {
                return "The background music must be a file uploaded here";
            }
            if (m.volume() < 0 || m.volume() > 2) {
                return "Music volume must be between 0% and 200%";
            }
            if (m.startMs() < 0 || (durationMs != null && m.startMs() >= durationMs)) {
                return "The music must start within the video";
            }
        }
        return null;
    }

    public static boolean isUploadKey(String key) {
        return key != null && key.startsWith(UPLOAD_PREFIX) && !key.contains("..") && key.length() > UPLOAD_PREFIX.length();
    }

    /** What the edit changes, in a line, for the review list ("Volume 150%, fade out 2 s, mono"). */
    public static String describe(Spec s) {
        List<String> parts = new ArrayList<>();
        if (s.replaceKey() != null) {
            parts.add("replaced sound");
        }
        if (!s.clips().isEmpty()) {
            parts.add(s.clips().size() + (s.clips().size() == 1 ? " clip" : " clips"));
        }
        if (!s.mutes().isEmpty()) {
            parts.add(s.mutes().size() + " muted " + (s.mutes().size() == 1 ? "range" : "ranges"));
        }
        if (s.volume() != 1) {
            parts.add(s.volume() == 0 ? "muted" : "volume " + Math.round(s.volume() * 100) + "%");
        }
        if (s.fadeInMs() > 0) {
            parts.add("fade in " + num(s.fadeInMs() / 1000.0) + " s");
        }
        if (s.fadeOutMs() > 0) {
            parts.add("fade out " + num(s.fadeOutMs() / 1000.0) + " s");
        }
        if (s.normalize()) {
            parts.add("normalized");
        }
        if (!"OFF".equals(s.denoise())) {
            parts.add(("LIGHT".equals(s.denoise()) ? "light" : "strong") + " noise reduction");
        }
        if (s.enhanceVoice()) {
            parts.add("voice enhanced");
        }
        if (s.music() != null) {
            parts.add("background music " + Math.round(s.music().volume() * 100) + "%" + (s.music().duck() ? " (ducked)" : ""));
        }
        if (s.balance() != 0) {
            parts.add("balance " + (s.balance() < 0 ? "L " : "R ") + Math.round(Math.abs(s.balance()) * 100) + "%");
        }
        if (s.pitchSemitones() != 0) {
            parts.add("pitch " + (s.pitchSemitones() > 0 ? "+" : "") + num(s.pitchSemitones()) + " st");
        }
        if (s.speed() != 1) {
            parts.add(num(s.speed()) + "× speed");
        }
        if (!"KEEP".equals(s.channels())) {
            parts.add(s.channels().toLowerCase());
        }
        String text = String.join(", ", parts);
        text = text.isEmpty() ? "No changes" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text.length() > 300 ? text.substring(0, 297) + "…" : text;
    }

    // ── graph ────────────────────────────────────────────────────────────

    /**
     * Inputs: 0 = the video; 1 = the replacement audio (when replaceKey is
     * set); then the music. Produces [aout], and [vout] when the speed changes
     * the picture too. Everything is mixed as 48 kHz stereo over silence
     * exactly as long as the video, so the sound always lines up with the picture.
     */
    public static Graph build(Spec s, Inputs in) {
        List<String> graph = new ArrayList<>();
        String videoSec = sec(in.videoDurationMs());

        // 1. The source sound (silence when the video has none and nothing replaces it).
        String source;
        if (s.replaceKey() != null) {
            source = "[1:a]";
        } else if (in.videoHasAudio()) {
            source = "[0:a]";
        } else {
            graph.add("anullsrc=r=" + RATE + ":cl=stereo,atrim=end=" + videoSec + "[silence]");
            source = "[silence]";
        }
        graph.add(source + "aformat=sample_fmts=fltp:sample_rates=" + RATE + ":channel_layouts=stereo[src]");
        // Everything is mixed over silence exactly as long as the video (amix
        // duration=first), which pads short sound and cuts long sound. apad
        // after amix doesn't: ffmpeg drops the padding, or never ends at all.
        graph.add("anullsrc=r=" + RATE + ":cl=stereo,atrim=end=" + videoSec + "[base]");

        // 2. Clips: cut, set their level, place them, mix.
        List<Clip> clips = s.clips();
        if (clips.isEmpty()) {
            graph.add("[base][src]amix=inputs=2:duration=first:normalize=0[placed]");
        } else {
            int n = clips.size();
            if (n > 1) {
                StringBuilder split = new StringBuilder("[src]asplit=" + n);
                for (int i = 0; i < n; i++) {
                    split.append("[s").append(i).append(']');
                }
                graph.add(split.toString());
            }
            StringBuilder mixInputs = new StringBuilder("[base]");
            for (int i = 0; i < n; i++) {
                Clip c = clips.get(i);
                List<String> f = new ArrayList<>();
                f.add("atrim=start=" + sec(c.srcStartMs()) + (c.srcEndMs() == Long.MAX_VALUE ? "" : ":end=" + sec(c.srcEndMs())));
                f.add("asetpts=PTS-STARTPTS");
                if (c.gain() != 1) {
                    f.add("volume=" + num(c.gain()));
                }
                if (c.atMs() > 0) {
                    f.add("adelay=delays=" + c.atMs() + ":all=1");
                }
                graph.add((n > 1 ? "[s" + i + "]" : "[src]") + String.join(",", f) + "[c" + i + "]");
                mixInputs.append("[c").append(i).append(']');
            }
            graph.add(mixInputs + "amix=inputs=" + (n + 1) + ":duration=first:normalize=0[placed]");
        }

        // 3. The main sound's own treatment.
        List<String> main = new ArrayList<>();
        for (Range r : s.mutes()) {
            main.add("volume=0:enable='between(t," + sec(r.startMs()) + "," + sec(r.endMs()) + ")'");
        }
        if (s.volume() != 1) {
            main.add("volume=" + num(s.volume()));
        }
        switch (s.denoise()) {
            case "LIGHT" -> main.add("afftdn=nr=12:nf=-40");
            case "STRONG" -> main.add("afftdn=nr=30:nf=-35");
            default -> {
            }
        }
        if (s.enhanceVoice()) {
            // Cut rumble and hiss, lift presence (~3 kHz), even out the level.
            main.add("highpass=f=80");
            main.add("lowpass=f=12000");
            main.add("equalizer=f=3000:t=q:w=1:g=3");
            main.add("acompressor=threshold=0.125:ratio=3:attack=5:release=100:makeup=1.5");
        }
        if (main.isEmpty()) {
            main.add("anull");
        }
        graph.add("[placed]" + String.join(",", main) + "[main]");
        String current = "[main]";

        // 4. Background music under it — quieter while there's speech when ducking.
        if (s.music() != null) {
            Music m = s.music();
            int musicInput = s.replaceKey() != null ? 2 : 1;
            List<String> mf = new ArrayList<>();
            mf.add("aformat=sample_fmts=fltp:sample_rates=" + RATE + ":channel_layouts=stereo");
            if (m.volume() != 1) {
                mf.add("volume=" + num(m.volume()));
            }
            if (m.startMs() > 0) {
                mf.add("adelay=delays=" + m.startMs() + ":all=1");
            }
            graph.add("[" + musicInput + ":a]" + String.join(",", mf) + "[music]");
            if (m.duck()) {
                graph.add("[main]asplit=2[voice][key]");
                graph.add("[music][key]sidechaincompress=threshold=0.03:ratio=10:attack=20:release=400[ducked]");
                graph.add("[voice][ducked]amix=inputs=2:duration=first:normalize=0[withmusic]");
            } else {
                graph.add("[main][music]amix=inputs=2:duration=first:normalize=0[withmusic]");
            }
            current = "[withmusic]";
        }

        // 5. The finished mix: balance, pitch/speed, loudness, fades, channels.
        List<String> out = new ArrayList<>();
        if (s.balance() != 0) {
            double left = s.balance() > 0 ? 1 - s.balance() : 1;
            double right = s.balance() < 0 ? 1 + s.balance() : 1;
            out.add("pan=stereo|c0=" + num(left) + "*c0|c1=" + num(right) + "*c1");
        }
        double tempo = s.speed();
        if (s.pitchSemitones() != 0) {
            // Resampling shifts pitch and tempo together; atempo then puts the tempo back.
            double factor = Math.pow(2, s.pitchSemitones() / 12);
            out.add("asetrate=" + num(RATE * factor));
            out.add("aresample=" + RATE);
            tempo /= factor;
        }
        out.addAll(atempo(tempo));
        if (s.normalize()) {
            // EBU R128 at -16 LUFS (typical for online video); loudnorm works at 192 kHz internally.
            out.add("loudnorm=I=-16:TP=-1.5:LRA=11");
            out.add("aresample=" + RATE);
        }
        long outMs = Math.round(in.videoDurationMs() / s.speed());
        if (s.fadeInMs() > 0) {
            out.add("afade=t=in:st=0:d=" + sec(s.fadeInMs()));
        }
        if (s.fadeOutMs() > 0) {
            out.add("afade=t=out:st=" + sec(Math.max(0, outMs - s.fadeOutMs())) + ":d=" + sec(s.fadeOutMs()));
        }
        boolean mono = "MONO".equals(s.channels()) || ("KEEP".equals(s.channels()) && in.sourceMono());
        if (mono) {
            out.add("pan=mono|c0=0.5*c0+0.5*c1");
        }
        if (out.isEmpty()) {
            out.add("anull");
        }
        graph.add(current + String.join(",", out) + "[aout]");

        if (s.changesPicture()) {
            graph.add("[0:v]setpts=PTS/" + num(s.speed()) + "[vout]");
        }
        return new Graph(String.join(";", graph), s.changesPicture());
    }

    /** atempo only takes 0.5–2 per instance; chain as many as needed. Empty for 1×. */
    static List<String> atempo(double tempo) {
        List<String> chain = new ArrayList<>();
        double t = tempo;
        while (t > 2.0 + 1e-9) {
            chain.add("atempo=2");
            t /= 2;
        }
        while (t < 0.5 - 1e-9) {
            chain.add("atempo=0.5");
            t /= 0.5;
        }
        if (Math.abs(t - 1) > 1e-6) {
            chain.add("atempo=" + num(t));
        }
        return chain;
    }

    static String sec(long ms) {
        return num(ms / 1000.0);
    }

    static String num(double v) {
        BigDecimal d = BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
    }
}
