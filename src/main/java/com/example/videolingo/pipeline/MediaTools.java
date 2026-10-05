package com.example.videolingo.pipeline;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Runs yt-dlp and ffmpeg. Audio for speech-to-text is always normalised to
// mono 16 kHz 32 kbps MP3 — plenty for speech, and ~14 MB per hour, well
// under Whisper's 25 MB upload limit once split into chunks.
@Component
@RequiredArgsConstructor
public class MediaTools {

    /** Whisper chunk length. 10 minutes of 32 kbps audio is ~2.4 MB. */
    static final int CHUNK_SECONDS = 600;

    private final PipelineProperties props;

    /** The video's audio as speech-ready MP3 in the job's work directory. */
    public Path extractAudio(Video video, JobContext ctx) {
        return extractAudio(video, video.getVideoUrl(), ctx);
    }

    /** Same, reading an uploaded or linked file from `readableUrl` (a signed link when the bucket is private). */
    public Path extractAudio(Video video, String readableUrl, JobContext ctx) {
        Path out = ctx.workDir().resolve("audio.mp3");
        String input;
        if (video.getSource() == VideoSource.UPLOAD || video.getSource() == VideoSource.URL) {
            // ffmpeg reads http(s) directly and only pulls the audio it needs.
            input = readableUrl;
        } else {
            input = download(video, ctx).toString();
        }
        ctx.progress(60, "Converting audio");
        int maxSeconds = props.maxMinutes() * 60;
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-i",
                        input,
                        "-t",
                        String.valueOf(maxSeconds),
                        "-vn",
                        "-ac",
                        "1",
                        "-ar",
                        "16000",
                        "-b:a",
                        "32k",
                        out.toString()),
                Duration.ofMinutes(30),
                ctx,
                "ffmpeg");
        if (!Files.exists(out) || size(out) < 1000) {
            throw new JobFailure("The video has no audio track that could be read");
        }
        return out;
    }

    /** Splits audio into CHUNK_SECONDS pieces, in order. A short file comes back as-is. */
    public List<Path> split(Path audio, JobContext ctx) {
        Path dir = ctx.workDir().resolve("chunks");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new JobFailure("Couldn't create a temporary folder: " + e.getMessage(), e);
        }
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-i",
                        audio.toString(),
                        "-f",
                        "segment",
                        "-segment_time",
                        String.valueOf(CHUNK_SECONDS),
                        "-c",
                        "copy",
                        dir.resolve("chunk_%03d.mp3").toString()),
                Duration.ofMinutes(10),
                ctx,
                "ffmpeg");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> chunks = files.filter(p -> p.getFileName().toString().startsWith("chunk_"))
                    .sorted()
                    .toList();
            return chunks.isEmpty() ? List.of(audio) : chunks;
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the split audio: " + e.getMessage(), e);
        }
    }

    /** WAV → MP3 (mono, 64 kbps) for the dub track. */
    public Path encodeMp3(Path wav, JobContext ctx) {
        Path out = ctx.workDir().resolve("dub.mp3");
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-i",
                        wav.toString(),
                        "-ac",
                        "1",
                        "-b:a",
                        "64k",
                        out.toString()),
                Duration.ofMinutes(10),
                ctx,
                "ffmpeg");
        return out;
    }

    /** Highest resolution a downloaded video is fetched at. */
    static final int MAX_HEIGHT = 720;

    /** A link video as an MP4 (≤ 720p) in the job's work directory, via yt-dlp. */
    public Path downloadVideo(Video video, long maxMb, JobContext ctx) {
        ctx.progress(5, "Downloading the video from " + label(video.getSource()));
        String template = ctx.workDir().resolve("video.%(ext)s").toString();
        String h = String.valueOf(MAX_HEIGHT);
        List<String> command = new ArrayList<>(List.of(
                props.ytDlp(),
                "--no-playlist",
                "--no-progress",
                "--quiet",
                "--no-warnings",
                // Prefer MP4/M4A (plays everywhere); else anything ≤ 720p, remuxed to MP4.
                "-f",
                "bv*[height<=" + h + "][ext=mp4]+ba[ext=m4a]/b[height<=" + h + "][ext=mp4]/bv*[height<=" + h
                        + "]+ba/b[height<=" + h + "]/b",
                "--merge-output-format",
                "mp4",
                "--remux-video",
                "mp4",
                "--max-filesize",
                maxMb + "M",
                "--match-filter",
                "duration < " + (props.maxMinutes() * 60),
                "-o",
                template));
        if (!props.ffmpeg().equals("ffmpeg")) {
            command.addAll(List.of("--ffmpeg-location", props.ffmpeg()));
        }
        command.add(video.getVideoUrl());
        run(command, Duration.ofMinutes(60), ctx, "yt-dlp");
        try (Stream<Path> files = Files.list(ctx.workDir())) {
            return files.filter(p -> p.getFileName().toString().equals("video.mp4"))
                    .findFirst()
                    .orElseThrow(() -> new JobFailure(
                            "yt-dlp didn't produce a video — it may be private, removed, age-restricted, "
                                    + "larger than " + maxMb + " MB, or longer than " + props.maxMinutes()
                                    + " minutes"));
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the download: " + e.getMessage(), e);
        }
    }

    /** The video (file or URL) with `audio` as its only sound track. The picture is copied, not re-encoded. */
    /**
     * Draws the cues of `srt` into the picture (so they show in any player).
     * The picture has to be re-encoded, which is the slow part. Scripts the
     * server has no font for (Khmer, Thai…) render as boxes — install e.g.
     * Noto fonts on the worker.
     */
    public Path burnSubtitles(String video, Path srt, JobContext ctx) {
        Path out = ctx.workDir().resolve("with-subtitles.mp4");
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                video,
                "-vf",
                "subtitles=" + filterPath(srt),
                "-map",
                "0:v:0",
                "-map",
                "0:a?"));
        command.addAll(h264(22));
        command.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(120), ctx, "ffmpeg");
        return out;
    }

    /**
     * Each box blurred in place: the picture is split, the box cut out, blurred and laid back over it. They chain
     * with "," like any other filter, so the crop and the rest follow them. The radius stays under a quarter of the
     * box's shorter side (the colour planes are half the size).
     */
    static List<String> blurFilters(List<VideoEditRules.BlurBox> boxes) {
        List<String> filters = new ArrayList<>();
        if (boxes == null) {
            return filters;
        }
        for (int i = 0; i < boxes.size(); i++) {
            VideoEditRules.BlurBox b = boxes.get(i);
            int radius = Math.max(1, Math.min(40, Math.min(b.w(), b.h()) / 4 - 1));
            filters.add("split=2[bm" + i + "][bt" + i + "];[bt" + i + "]crop=" + b.w() + ":" + b.h() + ":" + b.x() + ":"
                    + b.y()
                    + ",boxblur=luma_radius=" + radius + ":luma_power=2[bb" + i + "];[bm" + i + "][bb" + i + "]overlay="
                    + b.x()
                    + ":" + b.y());
        }
        return filters;
    }

    /** Plays one part of the clip at another speed; the sound keeps its pitch. `clipMs` = the clip's length. */
    public Path speedRange(String video, VideoEditRules.SpeedRange r, boolean hasAudio, long clipMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("ramped.mp4");
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                video,
                "-filter_complex",
                speedGraph(r, hasAudio, clipMs),
                "-map",
                "[v]"));
        if (hasAudio) {
            command.addAll(List.of("-map", "[a]", "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(h264(20));
        command.addAll(List.of("-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
    }

    /** Before / during / after, each a piece of the clip (empty ones left out), joined again. */
    static String speedGraph(VideoEditRules.SpeedRange r, boolean hasAudio, long clipMs) {
        java.util.function.LongFunction<String> sec = ms -> String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0);
        List<String> parts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String speed = String.format(java.util.Locale.ROOT, "%s", r.speed());
        String[][] pieces = {
            {r.startMs() > 0 ? "end=" + sec.apply(r.startMs()) : null, "1"},
            {"start=" + sec.apply(r.startMs()) + ":end=" + sec.apply(r.endMs()), speed},
            {r.endMs() < clipMs ? "start=" + sec.apply(r.endMs()) : null, "1"}
        };
        int n = 0;
        for (String[] piece : pieces) {
            if (piece[0] == null) {
                continue;
            }
            boolean changed = !piece[1].equals("1");
            parts.add("[0:v]trim=" + piece[0] + ",setpts=" + (changed ? "(PTS-STARTPTS)/" + piece[1] : "PTS-STARTPTS")
                    + "[v" + n + "]");
            if (hasAudio) {
                parts.add("[0:a]atrim=" + piece[0] + ",asetpts=PTS-STARTPTS"
                        + (changed ? "," + String.join(",", atempoChain(Double.parseDouble(piece[1]))) : "") + "[a" + n
                        + "]");
            }
            labels.add("[v" + n + "]" + (hasAudio ? "[a" + n + "]" : ""));
            n++;
        }
        parts.add(String.join("", labels) + "concat=n=" + n + ":v=1:a=" + (hasAudio ? 1 : 0) + "[v]"
                + (hasAudio ? "[a]" : ""));
        return String.join(";", parts);
    }

    /** atempo steps for a speed: each step between 0.5 and 2 (so 0.25 = 0.5, 0.5 and 4 = 2, 2). */
    static List<String> atempoChain(double speed) {
        List<String> steps = new ArrayList<>();
        double left = speed;
        while (left < 0.5) {
            steps.add("atempo=0.5");
            left /= 0.5;
        }
        while (left > 2) {
            steps.add("atempo=2");
            left /= 2;
        }
        steps.add("atempo=" + String.format(java.util.Locale.ROOT, "%s", Math.round(left * 1000) / 1000.0));
        return steps;
    }

    /** `second` over `video` in a corner, `sizePct` of its width, muted; the main video's sound and length are kept. */
    public Path pip(String video, String second, String corner, double sizePct, int width, JobContext ctx) {
        Path out = ctx.workDir().resolve("pip.mp4");
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                video,
                "-i",
                second,
                "-filter_complex",
                pipGraph(corner, sizePct, width),
                "-map",
                "[v]",
                "-map",
                "0:a?",
                "-c:a",
                "aac",
                "-b:a",
                "128k"));
        command.addAll(h264(20));
        command.addAll(List.of("-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
    }

    static String pipGraph(String corner, double sizePct, int width) {
        int w = Math.max(2, (int) Math.round(width * sizePct / 100 / 2) * 2);
        int margin = Math.max(2, (int) Math.round(width * 0.03));
        String c = corner == null ? "BOTTOM_RIGHT" : corner;
        String x = c.endsWith("LEFT") ? String.valueOf(margin) : "W-w-" + margin;
        String y = c.startsWith("TOP") ? String.valueOf(margin) : "H-h-" + margin;
        return "[1:v]scale=" + w + ":-2,setsar=1[p];[0:v][p]overlay=" + x + ":" + y + ":eof_action=pass[v]";
    }

    /** A silent title card video: `png` (the text, and the logo above it if any) centred on `background`, fading in and out. */
    public Path card(
            Path png,
            String background,
            int width,
            int height,
            long durationMs,
            boolean sound,
            String name,
            JobContext ctx) {
        Path out = ctx.workDir().resolve(name + ".mp4");
        String d = String.format(java.util.Locale.ROOT, "%.3f", durationMs / 1000.0);
        String fadeOut = String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, durationMs - 400) / 1000.0);
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "color=c=0x" + background.substring(1) + ":s=" + width + "x" + height + ":r=" + MergeRules.FPS + ":d="
                        + d,
                "-i",
                png.toString()));
        if (sound) {
            command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=" + MergeRules.SAMPLE_RATE + ":cl=stereo"));
        }
        command.addAll(List.of(
                "-filter_complex",
                "[0:v][1:v]overlay=(W-w)/2:(H-h)/2,fade=t=in:st=0:d=0.4,fade=t=out:st=" + fadeOut
                        + ":d=0.4,format=yuv420p[v]",
                "-map",
                "[v]"));
        if (sound) {
            command.addAll(List.of("-map", "2:a", "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(List.of("-t", d));
        command.addAll(h264(20));
        command.add(out.toString());
        run(command, Duration.ofMinutes(10), ctx, "ffmpeg");
        return out;
    }

    /** Joins the parts in order (cards and the clip), all brought to one size, frame rate and sound format. */
    public Path join(List<String> parts, int width, int height, boolean sound, JobContext ctx) {
        Path out = ctx.workDir().resolve("with-cards.mp4");
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y"));
        for (String part : parts) {
            command.addAll(List.of("-i", part));
        }
        command.addAll(List.of("-filter_complex", joinGraph(parts.size(), width, height, sound), "-map", "[v]"));
        if (sound) {
            command.addAll(List.of("-map", "[a]", "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(h264(20));
        command.addAll(List.of("-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
    }

    static String joinGraph(int n, int width, int height, boolean sound) {
        StringBuilder g = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < n; i++) {
            g.append("[")
                    .append(i)
                    .append(":v]scale=")
                    .append(width)
                    .append(":")
                    .append(height)
                    .append(":force_original_aspect_ratio=decrease,pad=")
                    .append(width)
                    .append(":")
                    .append(height)
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=")
                    .append(MergeRules.FPS)
                    .append(",format=yuv420p[v")
                    .append(i)
                    .append("];");
            labels.append("[v").append(i).append("]");
            if (sound) {
                g.append("[")
                        .append(i)
                        .append(":a]aformat=sample_rates=")
                        .append(MergeRules.SAMPLE_RATE)
                        .append(":channel_layouts=stereo[a")
                        .append(i)
                        .append("];");
                labels.append("[a").append(i).append("]");
            }
        }
        return g.append(labels)
                .append("concat=n=")
                .append(n)
                .append(":v=1:a=")
                .append(sound ? 1 : 0)
                .append("[v]")
                .append(sound ? "[a]" : "")
                .toString();
    }

    /** Holds the frame at `atMs` for `durationMs`, the sound pausing (silence) for as long; the rest plays on after. */
    public Path freeze(String video, long atMs, long durationMs, boolean hasAudio, JobContext ctx) {
        Path out = ctx.workDir().resolve("frozen.mp4");
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                video,
                "-filter_complex",
                freezeGraph(atMs, durationMs, hasAudio),
                "-map",
                "[v]"));
        if (hasAudio) {
            command.addAll(List.of("-map", "[a]", "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(h264(20));
        command.addAll(List.of("-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
    }

    /** The picture up to `atMs` with its last frame held, then the rest; the sound gets the same gap as silence. */
    static String freezeGraph(long atMs, long durationMs, boolean hasAudio) {
        String at = String.format(java.util.Locale.ROOT, "%.3f", atMs / 1000.0);
        String hold = String.format(java.util.Locale.ROOT, "%.3f", durationMs / 1000.0);
        String graph = "[0:v]trim=end=" + at + ",setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=" + hold
                + "[v1];[0:v]trim=start=" + at + ",setpts=PTS-STARTPTS[v2];[v1][v2]concat=n=2:v=1:a=0[v]";
        if (hasAudio) {
            graph += ";[0:a]atrim=end=" + at + ",asetpts=PTS-STARTPTS,apad=pad_dur=" + hold + "[a1];[0:a]atrim=start="
                    + at + ",asetpts=PTS-STARTPTS[a2];[a1][a2]concat=n=2:v=0:a=1[a]";
        }
        return graph;
    }

    /** A looping GIF of startMs–endMs, `width` px wide, with its own colour palette so it isn't banded. */
    public Path gif(String video, long startMs, long endMs, int width, int fps, JobContext ctx) {
        Path out = ctx.workDir().resolve("clip.gif");
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-ss",
                        millis(startMs),
                        "-t",
                        millis(endMs - startMs),
                        "-i",
                        video,
                        "-vf",
                        gifFilter(width, fps),
                        "-loop",
                        "0",
                        out.toString()),
                Duration.ofMinutes(20),
                ctx,
                "ffmpeg");
        return out;
    }

    static String gifFilter(int width, int fps) {
        return "fps=" + fps + ",scale=" + width + ":-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse";
    }

    /** The frame at `atMs` as a JPG. */
    public Path still(String video, long atMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("still.jpg");
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-ss",
                        millis(atMs),
                        "-i",
                        video,
                        "-frames:v",
                        "1",
                        "-q:v",
                        "2",
                        out.toString()),
                Duration.ofMinutes(5),
                ctx,
                "ffmpeg");
        return out;
    }

    // A path as an ffmpeg filter option value: \, : and ' are special there.
    static String filterPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'");
    }

    public record CropRect(int x, int y, int w, int h) {}

    public record ScaleSize(int w, int h) {}

    /**
     * A [startMs, endMs) range of `video` (endMs null = to the end), optionally
     * cropped and/or scaled. Always re-encoded, not stream-copied, so the cut
     * lands exactly on the requested millisecond instead of the nearest keyframe.
     */
    public Path trim(String video, long startMs, Long endMs, CropRect crop, ScaleSize scale, JobContext ctx) {
        return trim(video, startMs, endMs, crop, scale, null, false, false, ctx);
    }

    /**
     * Same, and the picture can be turned (clockwise, in 90° steps) and flipped. The crop is on the original
     * picture, then the turn and flips, then the resize — so a resize is the size of the finished picture.
     */
    public Path trim(
            String video,
            long startMs,
            Long endMs,
            CropRect crop,
            ScaleSize scale,
            Integer rotate,
            boolean flipH,
            boolean flipV,
            JobContext ctx) {
        return trim(video, startMs, endMs, crop, scale, rotate, flipH, flipV, 0, ctx);
    }

    /**
     * Same, and when `padMs` > 0 the result runs that long past the end of the video: the last frame is held and the
     * sound is silent there (the end time still caps the length, so a little extra padding is harmless).
     */
    public Path trim(
            String video,
            long startMs,
            Long endMs,
            CropRect crop,
            ScaleSize scale,
            Integer rotate,
            boolean flipH,
            boolean flipV,
            long padMs,
            JobContext ctx) {
        return trim(video, startMs, endMs, crop, scale, rotate, flipH, flipV, padMs, null, ctx);
    }

    /** Same, and the picture gets a look (see VideoEditRules.Look) after the crop, turn and resize. */
    public Path trim(
            String video,
            long startMs,
            Long endMs,
            CropRect crop,
            ScaleSize scale,
            Integer rotate,
            boolean flipH,
            boolean flipV,
            long padMs,
            VideoEditRules.Look look,
            JobContext ctx) {
        return trim(video, startMs, endMs, crop, scale, rotate, flipH, flipV, padMs, look, null, null, ctx);
    }

    /**
     * Same, and an effect (see VideoEditRules.EFFECTS) and a fade in/out. The order is: crop, turn, resize, look,
     * effect, held end, slow zoom, fade — so a zoom and the fades run to the very end of an extended trim.
     */
    public Path trim(
            String video,
            long startMs,
            Long endMs,
            CropRect crop,
            ScaleSize scale,
            Integer rotate,
            boolean flipH,
            boolean flipV,
            long padMs,
            VideoEditRules.Look look,
            String effect,
            VideoEditRules.Fade fade,
            JobContext ctx) {
        return trim(
                video, startMs, endMs, crop, scale, rotate, flipH, flipV, padMs, look, effect, fade, List.of(), ctx);
    }

    /** Same, with areas blurred first (in the original picture, before the crop). */
    public Path trim(
            String video,
            long startMs,
            Long endMs,
            CropRect crop,
            ScaleSize scale,
            Integer rotate,
            boolean flipH,
            boolean flipV,
            long padMs,
            VideoEditRules.Look look,
            String effect,
            VideoEditRules.Fade fade,
            List<VideoEditRules.BlurBox> blurs,
            JobContext ctx) {
        Path out = ctx.workDir().resolve("trim-" + startMs + "-" + (endMs == null ? "end" : endMs) + ".mp4");
        // -ss before -i jumps to the start instead of decoding everything before it; still exact to the frame, as
        // the clip is re-encoded. The clip's clock then starts at 0, and -t is its length.
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-ss", millis(startMs), "-i", video));
        if (endMs != null) {
            command.addAll(List.of("-t", millis(endMs - startMs)));
        }
        boolean zoom = "ZOOM_IN".equals(effect) || "ZOOM_OUT".equals(effect);
        boolean fading = fade != null && !fade.isNone();
        Probe info = padMs > 0 || zoom || fading ? probe(video, ctx.workDir()) : null;
        // Filters run on the clip's clock, which starts at 0.
        long clipMs = endMs != null
                ? endMs - startMs
                : info != null && info.durationMs() != null ? Math.max(0, info.durationMs() - startMs) : 0;

        List<String> filters = new ArrayList<>(blurFilters(blurs));
        filters.addAll(videoFilters(crop, rotate, flipH, flipV, scale));
        filters.addAll(lookFilters(look));
        filters.addAll(effectFilters(effect));
        if (padMs > 0) {
            filters.add(padFilter(padMs));
        }
        if (zoom) {
            int[] size = outputSize(crop, rotate, scale, info.width(), info.height());
            if (size == null) {
                throw new JobFailure("The video's picture size couldn't be read, so it can't be zoomed");
            }
            filters.addAll(zoomFilters(effect, 0, clipMs, size[0], size[1]));
        }
        if (fading) {
            filters.addAll(fadeFilters(fade, 0, clipMs, "fade"));
        }
        if (!filters.isEmpty()) {
            command.addAll(List.of("-vf", String.join(",", filters)));
        }
        boolean sound = info != null && info.hasAudio();
        List<String> audioFilters = new ArrayList<>();
        if (padMs > 0 && sound) {
            audioFilters.add("apad");
        }
        if (fading && sound) {
            audioFilters.addAll(fadeFilters(fade, 0, clipMs, "afade"));
        }
        if (!audioFilters.isEmpty()) {
            command.addAll(List.of("-af", String.join(",", audioFilters)));
        }
        command.addAll(h264(20));
        command.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg", endMs == null ? null : endMs - startMs);
        return out;
    }

    /** ffmpeg expression that is true for the moments inside any of the (merged) cuts, in seconds. */
    static String cutExpression(List<VideoEditRules.Segment> cuts) {
        return cuts.stream()
                .map(c -> c.endMs() == null
                        ? String.format(java.util.Locale.ROOT, "gte(t,%.3f)", c.startMs() / 1000.0)
                        : String.format(
                                java.util.Locale.ROOT,
                                "gte(t,%.3f)*lt(t,%.3f)",
                                c.startMs() / 1000.0,
                                c.endMs() / 1000.0))
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** The video with the given (merged) ranges taken out, picture and sound together; re-encoded so cuts are frame-exact. */
    public Path cut(String video, List<VideoEditRules.Segment> cuts, boolean hasAudio, Long keptMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("cut.mp4");
        String drop = cutExpression(cuts);
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                video,
                "-vf",
                "select='not(" + drop + ")',setpts=N/FRAME_RATE/TB"));
        if (hasAudio) {
            command.addAll(List.of("-af", "aselect='not(" + drop + ")',asetpts=N/SR/TB"));
        } else {
            command.add("-an");
        }
        command.addAll(h264(20));
        command.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg", keptMs);
        return out;
    }

    /** The -vf filters for a look (brightness, contrast, colour, warmth, tint, sharpen, blur, vignette), in that order. Empty for a plain look. */
    static List<String> lookFilters(VideoEditRules.Look look) {
        List<String> filters = new ArrayList<>();
        if (look == null || look.isPlain()) {
            return filters;
        }
        double saturation = look.grayscale() ? 0 : look.saturation();
        if (look.brightness() != 0 || look.contrast() != 1 || saturation != 1) {
            filters.add(String.format(
                    java.util.Locale.ROOT,
                    "eq=brightness=%.3f:contrast=%.3f:saturation=%.3f",
                    look.brightness(),
                    look.contrast(),
                    saturation));
        }
        if (look.warmth() != 0 && !look.grayscale()) {
            // Midtones toward orange (warm) or blue (cool).
            filters.add(String.format(
                    java.util.Locale.ROOT, "colorbalance=rm=%.3f:bm=%.3f", 0.3 * look.warmth(), -0.3 * look.warmth()));
        }
        if (look.sepia() && !look.grayscale()) {
            filters.add("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0:0:0:0:1");
        }
        if (look.sharpen()) {
            filters.add("unsharp=5:5:0.8:5:5:0");
        }
        if (look.blur() > 0) {
            filters.add(String.format(java.util.Locale.ROOT, "gblur=sigma=%.2f", look.blur()));
        }
        if (look.vignette()) {
            filters.add("vignette=PI/4");
        }
        return filters;
    }

    /** The -vf filters for an effect that stays the same through the clip; the slow zooms are added by zoomFilters. */
    static List<String> effectFilters(String effect) {
        if ("GLITCH".equals(effect)) {
            return List.of("rgbashift=rh=-6:bh=6", "noise=alls=10:allf=t");
        }
        if ("OLD_FILM".equals(effect)) {
            return List.of(
                    "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0:0:0:0:1",
                    "noise=alls=20:allf=t+u",
                    "vignette=PI/4",
                    "eq=brightness='0.03*sin(40*t)':eval=frame");
        }
        return List.of();
    }

    /**
     * A slow push-in (or pull-back) over the clip: the picture is scaled up frame by frame (by up to
     * VideoEditRules.ZOOM_AMOUNT) and cut back to its own size around the centre. Times are on the source's clock.
     */
    static List<String> zoomFilters(String effect, long startMs, long endMs, int width, int height) {
        double from = startMs / 1000.0;
        double length = Math.max(0.001, (endMs - startMs) / 1000.0);
        String progress = String.format(java.util.Locale.ROOT, "min(1,max(0,(t-%.3f)/%.3f))", from, length);
        String amount = "ZOOM_OUT".equals(effect) ? "(1-" + progress + ")" : progress;
        return List.of(
                String.format(
                        java.util.Locale.ROOT,
                        "scale=w='trunc(iw*(1+%.3f*%s)/2)*2':h=-2:eval=frame",
                        VideoEditRules.ZOOM_AMOUNT,
                        amount),
                "crop=" + width + ":" + height);
    }

    /** Fade in at the start and out at the end; `kind` is "fade" (picture, to the fade's colour) or "afade" (sound). */
    static List<String> fadeFilters(VideoEditRules.Fade fade, long startMs, long endMs, String kind) {
        List<String> filters = new ArrayList<>();
        String color = "fade".equals(kind) && fade.white() ? ":color=white" : "";
        if (fade.inMs() > 0) {
            filters.add(String.format(
                    java.util.Locale.ROOT,
                    "%s=t=in:st=%.3f:d=%.3f%s",
                    kind,
                    startMs / 1000.0,
                    fade.inMs() / 1000.0,
                    color));
        }
        if (fade.outMs() > 0) {
            filters.add(String.format(
                    java.util.Locale.ROOT,
                    "%s=t=out:st=%.3f:d=%.3f%s",
                    kind,
                    Math.max(startMs, endMs - fade.outMs()) / 1000.0,
                    fade.outMs() / 1000.0,
                    color));
        }
        return filters;
    }

    /** The picture size after crop, turn and resize; null when the source's size isn't known and nothing sets it. */
    static int[] outputSize(CropRect crop, Integer rotate, ScaleSize scale, Integer width, Integer height) {
        if (scale != null) {
            return new int[] {scale.w(), scale.h()};
        }
        Integer w = crop != null ? Integer.valueOf(crop.w()) : width;
        Integer h = crop != null ? Integer.valueOf(crop.h()) : height;
        if (w == null || h == null) {
            return null;
        }
        return VideoEditRules.swapsSides(rotate) ? new int[] {h, w} : new int[] {w, h};
    }

    /** Holds the last frame for `padMs` (plus a second of slack; -to caps the length). */
    static String padFilter(long padMs) {
        return String.format(java.util.Locale.ROOT, "tpad=stop_mode=clone:stop_duration=%.3f", (padMs + 1000) / 1000.0);
    }

    /** The -vf filters for a crop, a turn and flips, then a resize — in that order. Empty when none is asked for. */
    static List<String> videoFilters(CropRect crop, Integer rotate, boolean flipH, boolean flipV, ScaleSize scale) {
        List<String> filters = new ArrayList<>();
        if (crop != null) {
            filters.add("crop=" + crop.w() + ":" + crop.h() + ":" + crop.x() + ":" + crop.y());
        }
        if (rotate != null) {
            switch (rotate) {
                case 90 -> filters.add("transpose=1");
                case 180 -> filters.add("hflip,vflip");
                case 270 -> filters.add("transpose=2");
                default -> {}
            }
        }
        if (flipH) {
            filters.add("hflip");
        }
        if (flipV) {
            filters.add("vflip");
        }
        if (scale != null) {
            filters.add("scale=" + scale.w() + ":" + scale.h());
        }
        return filters;
    }

    /**
     * The H.264 settings every re-encoded video gets. yuv420p because a 10-bit or 4:4:4 source would otherwise
     * give an MP4 that most browsers and phones can't play.
     */
    static List<String> h264(int crf) {
        return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p");
    }

    // ffmpeg's -ss/-to want HH:MM:SS.mmm.
    private static String millis(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        double s = (ms % 60_000) / 1000.0;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%06.3f", h, m, s);
    }

    public Path replaceAudio(String video, Path audio, JobContext ctx) {
        Path out = ctx.workDir().resolve("with-voice.mp4");
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-i",
                        video,
                        "-i",
                        audio.toString(),
                        "-map",
                        "0:v:0",
                        "-map",
                        "1:a:0",
                        "-c:v",
                        "copy",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "128k",
                        "-movflags",
                        "+faststart",
                        out.toString()),
                Duration.ofMinutes(60),
                ctx,
                "ffmpeg");
        return out;
    }

    /** What ffmpeg reports about a file. durationMs null when it can't tell (e.g. a live stream). */
    public record Probe(
            Long durationMs, boolean hasVideo, boolean hasAudio, boolean mono, Integer width, Integer height) {}

    private static final Pattern FRAME_SIZE = Pattern.compile(", (\\d{2,5})x(\\d{2,5})[ ,\\[]");

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");

    /** Reads a file's header with `ffmpeg -i` (no ffprobe needed). */
    public Probe probe(String input, Path workDir) {
        Path logFile = workDir.resolve("probe-" + Integer.toHexString(input.hashCode()) + ".log");
        try {
            Process process = new ProcessBuilder(props.ffmpeg(), "-hide_banner", "-i", input)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new JobFailure("ffmpeg took too long to read " + input);
            }
            // Exit code 1 is expected: no output file was named.
            return parseProbe(Files.readString(logFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JobFailure(
                    "ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted while reading " + input, e);
        }
    }

    static Probe parseProbe(String text) {
        Long durationMs = null;
        Matcher m = DURATION.matcher(text);
        if (m.find()) {
            durationMs = Math.round((Long.parseLong(m.group(1)) * 3600
                            + Long.parseLong(m.group(2)) * 60
                            + Double.parseDouble(m.group(3)))
                    * 1000);
        }
        boolean video = false;
        boolean audio = false;
        boolean mono = false;
        Integer width = null;
        Integer height = null;
        for (String line : text.split("\n")) {
            if (!line.contains("Stream #")) {
                continue;
            }
            // Cover art in audio files shows up as a video stream too.
            if (line.contains("Video:") && !line.contains("(attached pic)") && !video) {
                video = true;
                Matcher size = FRAME_SIZE.matcher(line);
                if (size.find()) {
                    width = Integer.parseInt(size.group(1));
                    height = Integer.parseInt(size.group(2));
                }
            }
            if (line.contains("Audio:") && !audio) {
                audio = true;
                mono = line.contains(" mono");
            }
        }
        return new Probe(durationMs, video, audio, mono, width, height);
    }

    /**
     * The video with its sound re-rendered by `graph` (AudioEditRules). The
     * picture is copied as-is unless the graph changes it (speed).
     */
    public Path editAudio(
            String video,
            Path replacement,
            Path music,
            boolean loopMusic,
            AudioEditRules.Graph graph,
            long outMs,
            JobContext ctx) {
        Path out = ctx.workDir().resolve("audio-edit.mp4");
        List<String> command =
                new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video));
        if (replacement != null) {
            command.addAll(List.of("-i", replacement.toString()));
        }
        if (music != null) {
            if (loopMusic) {
                command.addAll(List.of("-stream_loop", "-1"));
            }
            command.addAll(List.of("-i", music.toString()));
        }
        command.addAll(List.of("-filter_complex", graph.filter()));
        if (graph.picture()) {
            command.addAll(List.of("-map", "[vout]"));
            command.addAll(h264(20));
        } else {
            command.addAll(List.of("-map", "0:v:0?", "-c:v", "copy"));
        }
        command.addAll(List.of(
                "-map",
                "[aout]",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-t",
                millis(outMs),
                "-movflags",
                "+faststart",
                out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg", outMs);
        return out;
    }

    /** The sound of `input` as a standalone file: "MP3" (192 kbps) or "WAV" (16-bit PCM). */
    public Path exportAudio(String input, String format, JobContext ctx) {
        boolean wav = "WAV".equals(format);
        Path out = ctx.workDir().resolve(wav ? "audio.wav" : "audio-export.mp3");
        List<String> command = new ArrayList<>(List.of(
                props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input, "-vn", "-map", "0:a:0"));
        command.addAll(wav ? List.of("-c:a", "pcm_s16le") : List.of("-c:a", "libmp3lame", "-b:a", "192k"));
        command.add(out.toString());
        run(command, Duration.ofMinutes(30), ctx, "ffmpeg");
        if (!Files.exists(out) || size(out) < 100) {
            throw new JobFailure("The video has no audio track to extract");
        }
        return out;
    }

    /** 10 ms blocks at 4 kHz: plenty for drawing, and ~3.5 MB/hour of decoded audio read through a pipe. */
    static final int PEAK_RATE = 4000;

    static final int PEAK_BLOCK = 40;

    /**
     * The loudest level (0–1) in each of `points` equal slices of the sound
     * of `input` — what a waveform is drawn from. Decoded as mono 4 kHz PCM
     * and read straight from ffmpeg's output. Empty when there's no sound.
     */
    public record Peaks(float[] values, long durationMs) {}

    public Peaks peaks(String input, int points, Path workDir) {
        Path logFile = workDir.resolve("peaks.log");
        List<Float> blocks = new ArrayList<>();
        Process process;
        try {
            process = new ProcessBuilder(
                            props.ffmpeg(),
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-i",
                            input,
                            "-vn",
                            "-ac",
                            "1",
                            "-ar",
                            String.valueOf(PEAK_RATE),
                            "-f",
                            "s16le",
                            "-")
                    .redirectError(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new JobFailure(
                    "ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        }
        try (var in = new java.io.BufferedInputStream(process.getInputStream(), 1 << 16)) {
            int max = 0;
            int n = 0;
            int lo;
            while ((lo = in.read()) >= 0) {
                int hi = in.read();
                if (hi < 0) {
                    break;
                }
                int v = Math.abs((short) ((hi << 8) | lo));
                if (v > max) {
                    max = v;
                }
                if (++n == PEAK_BLOCK) {
                    blocks.add(max / 32768f);
                    max = 0;
                    n = 0;
                }
            }
            if (n > 0) {
                blocks.add(max / 32768f);
            }
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new JobFailure("Couldn't read the audio: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted while reading the audio", e);
        }
        return new Peaks(downsample(blocks, points), (long) blocks.size() * PEAK_BLOCK * 1000 / PEAK_RATE);
    }

    /** Max over equal slices; fewer blocks than points just gives one point per block. */
    static float[] downsample(List<Float> blocks, int points) {
        int total = blocks.size();
        if (total == 0) {
            return new float[0];
        }
        int n = Math.min(points, total);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int a = (int) ((long) i * total / n);
            int b = Math.max(a + 1, (int) ((long) (i + 1) * total / n));
            float m = 0;
            for (int j = a; j < b; j++) {
                m = Math.max(m, blocks.get(j));
            }
            out[i] = m;
        }
        return out;
    }

    /** Grid used for the "Auto-center" motion heatmap: fine enough to be useful, coarse enough to decode fast. */
    static final int MOTION_COLS = 24;

    static final int MOTION_ROWS = 14;
    /** Never look at more than this much of the video — a stable centroid doesn't need the whole thing. */
    static final int MOTION_MAX_SECONDS = 90;

    /**
     * A coarse (MOTION_COLS×MOTION_ROWS) heatmap of where the picture moves,
     * built by summing frame-to-frame pixel differences on a downscaled,
     * low-frame-rate decode — cheap even for a long video. Normalised so the
     * busiest cell is 1 (AutoCropRules.centroid expects that). All zero when
     * the video is static or has no frames to compare.
     */
    public float[] motionHeatmap(String input, Path workDir) {
        Path logFile = workDir.resolve("motion.log");
        Process process;
        try {
            process = new ProcessBuilder(
                            props.ffmpeg(),
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-t",
                            String.valueOf(MOTION_MAX_SECONDS),
                            "-i",
                            input,
                            "-vf",
                            "fps=2,scale=" + MOTION_COLS + ":" + MOTION_ROWS,
                            "-f",
                            "rawvideo",
                            "-pix_fmt",
                            "rgb24",
                            "-")
                    .redirectError(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new JobFailure(
                    "ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        }
        int frameBytes = MOTION_COLS * MOTION_ROWS * 3;
        float[] heat = new float[MOTION_COLS * MOTION_ROWS];
        byte[] prev = null;
        byte[] cur = new byte[frameBytes];
        try (var in = new java.io.BufferedInputStream(process.getInputStream(), 1 << 16)) {
            while (readFully(in, cur, frameBytes)) {
                if (prev != null) {
                    for (int cell = 0; cell < heat.length; cell++) {
                        int base = cell * 3;
                        int diff = Math.abs((cur[base] & 0xff) - (prev[base] & 0xff))
                                + Math.abs((cur[base + 1] & 0xff) - (prev[base + 1] & 0xff))
                                + Math.abs((cur[base + 2] & 0xff) - (prev[base + 2] & 0xff));
                        heat[cell] += diff;
                    }
                }
                byte[] swap = prev;
                prev = cur;
                cur = swap != null ? swap : new byte[frameBytes];
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new JobFailure("Couldn't read the video: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted while reading the video", e);
        }
        float max = 0;
        for (float v : heat) {
            max = Math.max(max, v);
        }
        if (max > 0) {
            for (int i = 0; i < heat.length; i++) {
                heat[i] /= max;
            }
        }
        return heat;
    }

    // Reads exactly `len` bytes into `buf`, or stops (returning false) at EOF partway through.
    private static boolean readFully(java.io.InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /**
     * The video with `layers` (PNG files, in drawing order) composited by
     * `graph` (OverlayRules). Each image is looped for the video's length, so
     * fades and slides have frames to work on. The picture is re-encoded; the
     * sound is copied.
     */
    public Path overlay(String video, List<Path> layers, OverlayRules.Graph graph, long durationMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("overlay.mp4");
        List<String> command =
                new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video));
        for (Path layer : layers) {
            command.addAll(List.of("-loop", "1", "-framerate", "25", "-t", millis(durationMs), "-i", layer.toString()));
        }
        command.addAll(List.of("-filter_complex", graph.filter(), "-map", "[vout]", "-map", "0:a?"));
        command.addAll(h264(20));
        command.addAll(List.of("-c:a", "copy", "-t", millis(durationMs), "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(120), ctx, "ffmpeg", durationMs);
        return out;
    }

    /**
     * A video made of pictures and a sound: the `covers` (none = the colour alone) fitted onto the
     * background colour, or `titlePng` written over it, with the sound as `audio`
     * and an optional moving waveform. See AudioToVideoRules.
     */
    public Path audioToVideo(
            AudioToVideoRules.Spec spec,
            Path audio,
            List<Path> covers,
            Path titlePng,
            AudioToVideoRules.Size frame,
            long durationMs,
            JobContext ctx) {
        Path out = ctx.workDir().resolve("audio-video.mp4");
        run(
                AudioToVideoRules.command(props.ffmpeg(), spec, audio, covers, titlePng, frame, durationMs, out),
                Duration.ofMinutes(120),
                ctx,
                "ffmpeg",
                durationMs);
        return out;
    }

    /**
     * A short test render of a look: the sound and the background colour only (no pictures or title), at 360p, for the
     * page's "test render". Runs on the spot, not as a job, so it has no progress or cancelling — only a time limit.
     */
    public Path audioToVideoQuick(AudioToVideoRules.Spec spec, Path audio, long durationMs, Path workDir) {
        Path out = workDir.resolve("preview.mp4");
        runQuick(
                AudioToVideoRules.command(
                        props.ffmpeg(), spec, audio, List.of(), null, AudioToVideoRules.size("360p"), durationMs, out),
                Duration.ofSeconds(90),
                workDir);
        return out;
    }

    private void runQuick(List<String> command, Duration timeout, Path workDir) {
        Path logFile = workDir.resolve("quick.log");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new JobFailure(
                    "ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        }
        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new JobFailure(
                        "The test render took longer than " + timeout.toSeconds() + " seconds and was stopped");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JobFailure("The test render was interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new JobFailure("The test render failed: " + tailOf(logFile));
        }
    }

    /** Several videos joined into one, in order. See MergeRules. */
    public Path merge(
            List<Path> inputs,
            List<MergeRules.Part> parts,
            AudioToVideoRules.Size frame,
            String transition,
            JobContext ctx) {
        Path out = ctx.workDir().resolve("merged.mp4");
        run(
                MergeRules.command(props.ffmpeg(), inputs, parts, frame, transition, out),
                Duration.ofMinutes(180),
                ctx,
                "ffmpeg",
                MergeRules.totalMs(parts, transition));
        return out;
    }

    /** One frame of a video as a JPEG, for a thumbnail. */
    public Path frame(Path video, long atMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("frame-" + atMs + ".jpg");
        run(
                List.of(
                        props.ffmpeg(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-ss",
                        millis(atMs),
                        "-i",
                        video.toString(),
                        "-frames:v",
                        "1",
                        "-q:v",
                        "3",
                        out.toString()),
                Duration.ofMinutes(2),
                ctx,
                "ffmpeg");
        return out;
    }

    // yt-dlp for YouTube / Vimeo / Facebook: best audio-only stream.
    private Path download(Video video, JobContext ctx) {
        ctx.progress(5, "Downloading audio from " + label(video.getSource()));
        String template = ctx.workDir().resolve("source.%(ext)s").toString();
        run(
                List.of(
                        props.ytDlp(),
                        "--no-playlist",
                        "--no-progress",
                        "--quiet",
                        "--no-warnings",
                        "-f",
                        "bestaudio/best",
                        "--match-filter",
                        "duration < " + (props.maxMinutes() * 60),
                        "-o",
                        template,
                        video.getVideoUrl()),
                Duration.ofMinutes(30),
                ctx,
                "yt-dlp");
        try (Stream<Path> files = Files.list(ctx.workDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith("source."))
                    .findFirst()
                    .orElseThrow(
                            () -> new JobFailure("yt-dlp didn't download anything — the video may be private, removed, "
                                    + "age-restricted, or longer than " + props.maxMinutes() + " minutes"));
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the download: " + e.getMessage(), e);
        }
    }

    /** ffmpeg writing nothing new for this long is taken as stuck (a filter graph that never ends, a dead input). Not final: tests shorten it. */
    static Duration stall = Duration.ofMinutes(5);

    private void run(List<String> command, Duration timeout, JobContext ctx, String tool) {
        run(command, timeout, ctx, tool, null);
    }

    // Runs a tool, polling for cancellation; stdout+stderr go to a log file
    // (reading pipes instead could block the process when a buffer fills).
    // ffmpeg also writes -progress to a file: with expectedMs (the length of
    // what it's producing) that becomes the job's progress, and a position
    // that stops moving for `stall` means it's stuck, so it's stopped then
    // instead of at the timeout.
    private void run(List<String> command, Duration timeout, JobContext ctx, String tool, Long expectedMs) {
        Path logFile = ctx.workDir().resolve(tool + ".log");
        Path progressFile = null;
        List<String> cmd = command;
        if (tool.equals("ffmpeg")) {
            progressFile = ctx.workDir().resolve("ffmpeg-progress-" + System.nanoTime() + ".txt");
            cmd = new ArrayList<>(command);
            cmd.addAll(1, List.of("-progress", progressFile.toString(), "-nostats"));
        }
        Process process;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
        } catch (IOException e) {
            String setting = tool.equals("yt-dlp") ? "YT_DLP_PATH" : "FFMPEG_PATH";
            throw new JobFailure(
                    tool + " isn't installed on the server (or isn't on the PATH). Install it, or set " + setting + ".",
                    e);
        }
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        long lastPosition = -1;
        long lastMove = started;
        try {
            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                long now = System.nanoTime();
                if (now > deadline) {
                    process.destroyForcibly();
                    throw new JobFailure(
                            tool + " took longer than " + timeout.toMinutes() + " minutes and was stopped");
                }
                if (progressFile != null) {
                    Long us = lastOutTimeUs(tailOf(progressFile));
                    if (us != null && us > lastPosition) {
                        lastPosition = us;
                        lastMove = now;
                        if (expectedMs != null && expectedMs > 0) {
                            ctx.progress((int) Math.min(99, us / 1000 * 100 / expectedMs), null);
                        }
                    } else if (now - lastMove > stall.toNanos()) {
                        process.destroyForcibly();
                        throw new JobFailure("ffmpeg stopped making progress"
                                + (lastPosition >= 0 ? " at " + clock(lastPosition / 1000) : "") + " for "
                                + describe(stall) + " and was stopped");
                    }
                }
                try {
                    ctx.checkpoint();
                } catch (JobCancelled e) {
                    process.destroyForcibly();
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted while running " + tool, e);
        }
        if (process.exitValue() != 0) {
            throw new JobFailure(tool + " failed: " + tail(logFile));
        }
    }

    /** The last output position (µs) in ffmpeg -progress output, or null when there's none yet. */
    static Long lastOutTimeUs(String progress) {
        Long last = null;
        for (String line : progress.split("\n")) {
            if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                String v = line.substring(line.indexOf('=') + 1).strip();
                try {
                    long n = Long.parseLong(v);
                    if (n >= 0) {
                        last = n;
                    }
                } catch (NumberFormatException ignored) {
                    // "N/A" before the first frame.
                }
            }
        }
        return last;
    }

    // The end of a growing file — enough for the latest progress block.
    private static String tailOf(Path file) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - 4096);
            byte[] buf = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String describe(Duration d) {
        return d.toMinutes() >= 1 ? d.toMinutes() + " minutes" : d.toSeconds() + " seconds";
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String tail(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            List<String> lines = new ArrayList<>(List.of(text.split("\n")));
            String last = String.join(" ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
            return last.isBlank() ? "no output" : (last.length() > 500 ? last.substring(last.length() - 500) : last);
        } catch (IOException e) {
            return "no output";
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String label(VideoSource source) {
        return switch (source) {
            case YOUTUBE -> "YouTube";
            case VIMEO -> "Vimeo";
            case FACEBOOK -> "Facebook";
            default -> "the link";
        };
    }
}
