package com.example.videolingo.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Joining several videos into one, in order. Clips rarely match — different
// sizes, frame rates, sound layouts, and some have no sound at all — so every
// clip is first brought to one size, one frame rate and one sound format, then
// concatenated, which means the result is re-encoded. Pure rules and the ffmpeg
// command, so they can be checked without running ffmpeg; the job itself is
// PipelineSteps.mergeJob.
public final class MergeRules {

    private MergeRules() {}

    public static final int MIN_PARTS = 2;
    public static final int MAX_PARTS = 10;
    /** Re-encoding is slow, so the joined video is capped. */
    public static final long MAX_TOTAL_MS = 3L * 60 * 60 * 1000;

    public static final List<String> TRANSITIONS = List.of("NONE", "FADE", "FADE_WHITE", "DISSOLVE", "WIPE", "SLIDE");
    public static final double FADE_SECONDS = 0.5;
    /** The joint of an overlapping transition (DISSOLVE, WIPE, SLIDE); the two clips play over each other for this long. */
    public static final double OVERLAP_SECONDS = 0.6;
    /** The ffmpeg xfade effect behind each overlapping transition. */
    static final Map<String, String> OVERLAPS = Map.of("DISSOLVE", "fade", "WIPE", "wipeleft", "SLIDE", "slideleft");

    public static final int FPS = 30;
    public static final int SAMPLE_RATE = 48_000;

    /** What the job learned about one clip by looking at the file. */
    public record Part(long durationMs, boolean hasAudio) {}

    /** Null = valid; a message otherwise. Checks the request, before any file is looked at. */
    public static String validate(List<Long> videoIds, String resolution, String transition) {
        if (videoIds == null || videoIds.size() < MIN_PARTS) {
            return "Pick at least " + MIN_PARTS + " videos to join";
        }
        if (videoIds.size() > MAX_PARTS) {
            return "At most " + MAX_PARTS + " videos can be joined at once";
        }
        if (videoIds.stream().anyMatch(id -> id == null)
                || videoIds.stream().distinct().count() != videoIds.size()) {
            return "Each video can only be used once";
        }
        if (resolution != null && !AudioToVideoRules.RESOLUTIONS.contains(resolution)) {
            return "Pick a size: " + String.join(", ", AudioToVideoRules.RESOLUTIONS);
        }
        if (transition != null && !TRANSITIONS.contains(transition)) {
            return "Pick a transition: " + String.join(", ", TRANSITIONS);
        }
        return null;
    }

    /** Null = valid; a message otherwise. Checks what the files turned out to hold. */
    public static String validateParts(List<Part> parts) {
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).durationMs() <= 0) {
                return "Couldn't read how long video " + (i + 1) + " is";
            }
            total += parts.get(i).durationMs();
        }
        if (total > MAX_TOTAL_MS) {
            return "The joined video would be " + formatLength(total) + " long; the limit is "
                    + formatLength(MAX_TOTAL_MS);
        }
        return null;
    }

    public static long totalMs(List<Part> parts) {
        return parts.stream().mapToLong(Part::durationMs).sum();
    }

    /** True for the transitions where the clips play over each other, which makes the result shorter than the sum of the clips. */
    public static boolean overlaps(String transition) {
        return transition != null && OVERLAPS.containsKey(transition);
    }

    /** How long the joint between two clips lasts: shortened for a clip too short to hold it. */
    static double overlapFor(long beforeMs, long afterMs) {
        return Math.min(OVERLAP_SECONDS, Math.min(beforeMs, afterMs) / 2000.0);
    }

    /** The length of the joined video: the clips' total, less the overlaps of an overlapping transition. */
    public static long totalMs(List<Part> parts, String transition) {
        long total = totalMs(parts);
        if (!overlaps(transition)) {
            return total;
        }
        for (int i = 1; i < parts.size(); i++) {
            total -= Math.round(
                    overlapFor(parts.get(i - 1).durationMs(), parts.get(i).durationMs()) * 1000);
        }
        return total;
    }

    static String formatLength(long ms) {
        long minutes = Math.round(ms / 60_000.0);
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** The fade at each joint is shortened for a clip too short to hold two of them. */
    static double fadeFor(long durationMs) {
        return Math.min(FADE_SECONDS, durationMs / 2000.0);
    }

    /**
     * The filter graph: each clip made the same, then joined. Inputs are the
     * clips in order; ends in labels [v] and [a]. With FADE, each joint dips
     * through black (and silence) — the first clip doesn't fade in, nor the last out.
     */
    static String filterGraph(List<Part> parts, AudioToVideoRules.Size size, String transition) {
        boolean fade = "FADE".equals(transition) || "FADE_WHITE".equals(transition);
        String dipColor = "FADE_WHITE".equals(transition) ? ":c=white" : "";
        boolean overlap = overlaps(transition);
        int n = parts.size();
        List<String> lines = new ArrayList<>();
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Part part = parts.get(i);
            double d = part.durationMs() / 1000.0;
            double f = fadeFor(part.durationMs());

            StringBuilder video = new StringBuilder("[" + i + ":v]scale=" + size.w() + ":" + size.h()
                    + ":force_original_aspect_ratio=decrease,pad=" + size.w() + ":" + size.h()
                    + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=" + FPS + ",format=yuv420p");
            // Sound: brought to one layout and rate, and held to exactly the clip's length so the joints don't drift.
            StringBuilder audio = new StringBuilder(
                    part.hasAudio()
                            ? "[" + i + ":a]aresample=" + SAMPLE_RATE
                                    + ",aformat=sample_fmts=fltp:channel_layouts=stereo,apad=whole_dur=" + seconds(d)
                                    + ",atrim=duration=" + seconds(d) + ",asetpts=PTS-STARTPTS"
                            : "anullsrc=r=" + SAMPLE_RATE + ":cl=stereo,atrim=duration=" + seconds(d)
                                    + ",asetpts=PTS-STARTPTS");
            if (fade && i > 0) {
                video.append(",fade=t=in:st=0:d=").append(seconds(f)).append(dipColor);
                audio.append(",afade=t=in:st=0:d=").append(seconds(f));
            }
            if (fade && i < n - 1) {
                video.append(",fade=t=out:st=")
                        .append(seconds(d - f))
                        .append(":d=")
                        .append(seconds(f))
                        .append(dipColor);
                audio.append(",afade=t=out:st=")
                        .append(seconds(d - f))
                        .append(":d=")
                        .append(seconds(f));
            }
            lines.add(video + "[v" + i + "]");
            lines.add(audio + "[a" + i + "]");
            concat.append("[v").append(i).append("][a").append(i).append("]");
        }
        if (overlap) {
            // Each clip plays over the end of the one before it: the picture with xfade, the sound with acrossfade.
            String effect = OVERLAPS.get(transition);
            double length = parts.get(0).durationMs() / 1000.0;
            String video = "[v0]";
            String audio = "[a0]";
            for (int i = 1; i < n; i++) {
                double o =
                        overlapFor(parts.get(i - 1).durationMs(), parts.get(i).durationMs());
                boolean last = i == n - 1;
                lines.add(video + "[v" + i + "]xfade=transition=" + effect + ":duration=" + seconds(o) + ":offset="
                        + seconds(length - o) + (last ? "[v]" : "[x" + i + "]"));
                lines.add(audio + "[a" + i + "]acrossfade=d=" + seconds(o) + (last ? "[a]" : "[y" + i + "]"));
                length += parts.get(i).durationMs() / 1000.0 - o;
                video = "[x" + i + "]";
                audio = "[y" + i + "]";
            }
            return String.join(";", lines);
        }
        lines.add(concat + "concat=n=" + n + ":v=1:a=1[v][a]");
        return String.join(";", lines);
    }

    public static List<String> command(
            String ffmpeg,
            List<Path> inputs,
            List<Part> parts,
            AudioToVideoRules.Size size,
            String transition,
            Path out) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        for (Path input : inputs) {
            cmd.addAll(List.of("-i", input.toString()));
        }
        cmd.addAll(List.of(
                "-filter_complex",
                filterGraph(parts, size, transition),
                "-map",
                "[v]",
                "-map",
                "[a]",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-r",
                String.valueOf(FPS),
                "-c:a",
                "aac",
                "-b:a",
                "160k",
                "-ar",
                String.valueOf(SAMPLE_RATE),
                "-movflags",
                "+faststart",
                out.toString()));
        return cmd;
    }

    // ── carrying transcripts over ─────────────────────────────────────────

    /** One transcript segment, as far as joining is concerned. */
    public record Seg(long startMs, long endMs, String text, String speaker) {}

    /** Where each clip starts in the joined video: the total length of the clips before it. */
    public static List<Long> offsets(List<Part> parts) {
        List<Long> out = new ArrayList<>();
        long at = 0;
        for (Part part : parts) {
            out.add(at);
            at += part.durationMs();
        }
        return out;
    }

    /**
     * A clip's segments moved to where the clip sits in the joined video. A segment that
     * starts after the clip ends is dropped, and one that runs past the end is cut at it,
     * so text never spills into the next clip.
     */
    public static List<Seg> shift(List<Seg> segments, long offsetMs, long clipMs) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : segments) {
            if (s.startMs() >= clipMs) {
                continue;
            }
            long end = Math.min(s.endMs(), clipMs);
            out.add(new Seg(offsetMs + s.startMs(), offsetMs + Math.max(end, s.startMs() + 1), s.text(), s.speaker()));
        }
        return out;
    }

    /** The languages every video has a transcript in, in the order the first video lists them; a gap in one video would leave a hole, so those are left out. */
    public static List<String> commonLanguages(List<Set<String>> perVideo) {
        if (perVideo.isEmpty()) {
            return List.of();
        }
        Set<String> common = new LinkedHashSet<>(perVideo.get(0));
        for (Set<String> languages : perVideo) {
            common.retainAll(languages);
        }
        return new ArrayList<>(common);
    }

    /** A short line for the job log and the job list. */
    public static String describe(int count, String resolution, String transition) {
        AudioToVideoRules.Size size = AudioToVideoRules.size(resolution);
        String joint = switch (transition == null ? "NONE" : transition) {
            case "FADE" -> ", fades";
            case "FADE_WHITE" -> ", fades through white";
            case "DISSOLVE" -> ", dissolves";
            case "WIPE" -> ", wipes";
            case "SLIDE" -> ", slides";
            default -> "";
        };
        return "Join " + count + " videos (" + size.w() + "×" + size.h() + joint + ")";
    }
}
