package com.example.videolingo.pipeline;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        Path out = ctx.workDir().resolve("audio.mp3");
        String input;
        if (video.getSource() == VideoSource.UPLOAD || video.getSource() == VideoSource.URL) {
            // ffmpeg reads http(s) directly and only pulls the audio it needs.
            input = video.getVideoUrl();
        } else {
            input = download(video, ctx).toString();
        }
        ctx.progress(60, "Converting audio");
        int maxSeconds = props.maxMinutes() * 60;
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input,
                "-t", String.valueOf(maxSeconds), "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", out.toString()),
                Duration.ofMinutes(30), ctx, "ffmpeg");
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
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", audio.toString(),
                "-f", "segment", "-segment_time", String.valueOf(CHUNK_SECONDS), "-c", "copy",
                dir.resolve("chunk_%03d.mp3").toString()), Duration.ofMinutes(10), ctx, "ffmpeg");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> chunks = files.filter(p -> p.getFileName().toString().startsWith("chunk_")).sorted().toList();
            return chunks.isEmpty() ? List.of(audio) : chunks;
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the split audio: " + e.getMessage(), e);
        }
    }

    /** WAV → MP3 (mono, 64 kbps) for the dub track. */
    public Path encodeMp3(Path wav, JobContext ctx) {
        Path out = ctx.workDir().resolve("dub.mp3");
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", wav.toString(),
                "-ac", "1", "-b:a", "64k", out.toString()), Duration.ofMinutes(10), ctx, "ffmpeg");
        return out;
    }

    /** Highest resolution a downloaded video is fetched at. */
    static final int MAX_HEIGHT = 720;

    /** A link video as an MP4 (≤ 720p) in the job's work directory, via yt-dlp. */
    public Path downloadVideo(Video video, long maxMb, JobContext ctx) {
        ctx.progress(5, "Downloading the video from " + label(video.getSource()));
        String template = ctx.workDir().resolve("video.%(ext)s").toString();
        String h = String.valueOf(MAX_HEIGHT);
        List<String> command = new ArrayList<>(List.of(props.ytDlp(), "--no-playlist", "--no-progress", "--quiet", "--no-warnings",
                // Prefer MP4/M4A (plays everywhere); else anything ≤ 720p, remuxed to MP4.
                "-f", "bv*[height<=" + h + "][ext=mp4]+ba[ext=m4a]/b[height<=" + h + "][ext=mp4]/bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b",
                "--merge-output-format", "mp4", "--remux-video", "mp4",
                "--max-filesize", maxMb + "M",
                "--match-filter", "duration < " + (props.maxMinutes() * 60),
                "-o", template));
        if (!props.ffmpeg().equals("ffmpeg")) {
            command.addAll(List.of("--ffmpeg-location", props.ffmpeg()));
        }
        command.add(video.getVideoUrl());
        run(command, Duration.ofMinutes(60), ctx, "yt-dlp");
        try (Stream<Path> files = Files.list(ctx.workDir())) {
            return files.filter(p -> p.getFileName().toString().equals("video.mp4"))
                    .findFirst()
                    .orElseThrow(() -> new JobFailure("yt-dlp didn't produce a video — it may be private, removed, age-restricted, "
                            + "larger than " + maxMb + " MB, or longer than " + props.maxMinutes() + " minutes"));
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
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video,
                "-vf", "subtitles=" + filterPath(srt), "-map", "0:v:0", "-map", "0:a?",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", out.toString()), Duration.ofMinutes(120), ctx, "ffmpeg");
        return out;
    }

    // A path as an ffmpeg filter option value: \, : and ' are special there.
    static String filterPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'");
    }

    public record CropRect(int x, int y, int w, int h) {
    }

    public record ScaleSize(int w, int h) {
    }

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
    public Path trim(String video, long startMs, Long endMs, CropRect crop, ScaleSize scale, Integer rotate, boolean flipH, boolean flipV, JobContext ctx) {
        Path out = ctx.workDir().resolve("trim-" + startMs + "-" + (endMs == null ? "end" : endMs) + ".mp4");
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", video, "-ss", millis(startMs)));
        if (endMs != null) {
            command.addAll(List.of("-to", millis(endMs)));
        }
        List<String> filters = videoFilters(crop, rotate, flipH, flipV, scale);
        if (!filters.isEmpty()) {
            command.addAll(List.of("-vf", String.join(",", filters)));
        }
        command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg", endMs == null ? null : endMs - startMs);
        return out;
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
                default -> {
                }
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

    // ffmpeg's -ss/-to want HH:MM:SS.mmm.
    private static String millis(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        double s = (ms % 60_000) / 1000.0;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%06.3f", h, m, s);
    }

    public Path replaceAudio(String video, Path audio, JobContext ctx) {
        Path out = ctx.workDir().resolve("with-voice.mp4");
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video, "-i", audio.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", out.toString()), Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
    }

    /** What ffmpeg reports about a file. durationMs null when it can't tell (e.g. a live stream). */
    public record Probe(Long durationMs, boolean hasVideo, boolean hasAudio, boolean mono, Integer width, Integer height) {
    }

    private static final Pattern FRAME_SIZE = Pattern.compile(", (\\d{2,5})x(\\d{2,5})[ ,\\[]");

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");

    /** Reads a file's header with `ffmpeg -i` (no ffprobe needed). */
    public Probe probe(String input, Path workDir) {
        Path logFile = workDir.resolve("probe-" + Integer.toHexString(input.hashCode()) + ".log");
        try {
            Process process = new ProcessBuilder(props.ffmpeg(), "-hide_banner", "-i", input)
                    .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new JobFailure("ffmpeg took too long to read " + input);
            }
            // Exit code 1 is expected: no output file was named.
            return parseProbe(Files.readString(logFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JobFailure("ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted while reading " + input, e);
        }
    }

    static Probe parseProbe(String text) {
        Long durationMs = null;
        Matcher m = DURATION.matcher(text);
        if (m.find()) {
            durationMs = Math.round((Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Double.parseDouble(m.group(3))) * 1000);
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
    public Path editAudio(String video, Path replacement, Path music, boolean loopMusic, AudioEditRules.Graph graph, long outMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("audio-edit.mp4");
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video));
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
            command.addAll(List.of("-map", "[vout]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20"));
        } else {
            command.addAll(List.of("-map", "0:v:0?", "-c:v", "copy"));
        }
        command.addAll(List.of("-map", "[aout]", "-c:a", "aac", "-b:a", "192k", "-t", millis(outMs), "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg", outMs);
        return out;
    }

    /** The sound of `input` as a standalone file: "MP3" (192 kbps) or "WAV" (16-bit PCM). */
    public Path exportAudio(String input, String format, JobContext ctx) {
        boolean wav = "WAV".equals(format);
        Path out = ctx.workDir().resolve(wav ? "audio.wav" : "audio-export.mp3");
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", input, "-vn", "-map", "0:a:0"));
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
    public record Peaks(float[] values, long durationMs) {
    }

    public Peaks peaks(String input, int points, Path workDir) {
        Path logFile = workDir.resolve("peaks.log");
        List<Float> blocks = new ArrayList<>();
        Process process;
        try {
            process = new ProcessBuilder(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-i", input, "-vn", "-ac", "1",
                    "-ar", String.valueOf(PEAK_RATE), "-f", "s16le", "-").redirectError(logFile.toFile()).start();
        } catch (IOException e) {
            throw new JobFailure("ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
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
            process = new ProcessBuilder(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-t", String.valueOf(MOTION_MAX_SECONDS), "-i", input,
                    "-vf", "fps=2,scale=" + MOTION_COLS + ":" + MOTION_ROWS, "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
                    .redirectError(logFile.toFile()).start();
        } catch (IOException e) {
            throw new JobFailure("ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
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
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-i", video));
        for (Path layer : layers) {
            command.addAll(List.of("-loop", "1", "-framerate", "25", "-t", millis(durationMs), "-i", layer.toString()));
        }
        command.addAll(List.of("-filter_complex", graph.filter(), "-map", "[vout]", "-map", "0:a?",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "copy",
                "-t", millis(durationMs), "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(120), ctx, "ffmpeg", durationMs);
        return out;
    }

    /**
     * A video made of pictures and a sound: the `covers` (none = the colour alone) fitted onto the
     * background colour, or `titlePng` written over it, with the sound as `audio`
     * and an optional moving waveform. See AudioToVideoRules.
     */
    public Path audioToVideo(AudioToVideoRules.Spec spec, Path audio, List<Path> covers, Path titlePng, AudioToVideoRules.Size frame, long durationMs,
                             JobContext ctx) {
        Path out = ctx.workDir().resolve("audio-video.mp4");
        run(AudioToVideoRules.command(props.ffmpeg(), spec, audio, covers, titlePng, frame, durationMs, out), Duration.ofMinutes(120), ctx, "ffmpeg",
                durationMs);
        return out;
    }

    /**
     * A short test render of a look: the sound and the background colour only (no pictures or title), at 360p, for the
     * page's "test render". Runs on the spot, not as a job, so it has no progress or cancelling — only a time limit.
     */
    public Path audioToVideoQuick(AudioToVideoRules.Spec spec, Path audio, long durationMs, Path workDir) {
        Path out = workDir.resolve("preview.mp4");
        runQuick(AudioToVideoRules.command(props.ffmpeg(), spec, audio, List.of(), null, AudioToVideoRules.size("360p"), durationMs, out), Duration.ofSeconds(90), workDir);
        return out;
    }

    private void runQuick(List<String> command, Duration timeout, Path workDir) {
        Path logFile = workDir.resolve("quick.log");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        } catch (IOException e) {
            throw new JobFailure("ffmpeg isn't installed on the server (or isn't on the PATH). Install it, or set FFMPEG_PATH.", e);
        }
        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new JobFailure("The test render took longer than " + timeout.toSeconds() + " seconds and was stopped");
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
    public Path merge(List<Path> inputs, List<MergeRules.Part> parts, AudioToVideoRules.Size frame, String transition, JobContext ctx) {
        Path out = ctx.workDir().resolve("merged.mp4");
        run(MergeRules.command(props.ffmpeg(), inputs, parts, frame, transition, out), Duration.ofMinutes(180), ctx, "ffmpeg", MergeRules.totalMs(parts));
        return out;
    }

    /** One frame of a video as a JPEG, for a thumbnail. */
    public Path frame(Path video, long atMs, JobContext ctx) {
        Path out = ctx.workDir().resolve("frame-" + atMs + ".jpg");
        run(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", "-ss", millis(atMs), "-i", video.toString(), "-frames:v", "1", "-q:v", "3",
                out.toString()), Duration.ofMinutes(2), ctx, "ffmpeg");
        return out;
    }

    // yt-dlp for YouTube / Vimeo / Facebook: best audio-only stream.
    private Path download(Video video, JobContext ctx) {
        ctx.progress(5, "Downloading audio from " + label(video.getSource()));
        String template = ctx.workDir().resolve("source.%(ext)s").toString();
        run(List.of(props.ytDlp(), "--no-playlist", "--no-progress", "--quiet", "--no-warnings",
                "-f", "bestaudio/best", "--match-filter", "duration < " + (props.maxMinutes() * 60),
                "-o", template, video.getVideoUrl()), Duration.ofMinutes(30), ctx, "yt-dlp");
        try (Stream<Path> files = Files.list(ctx.workDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith("source."))
                    .findFirst()
                    .orElseThrow(() -> new JobFailure("yt-dlp didn't download anything — the video may be private, removed, "
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
            process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        } catch (IOException e) {
            String setting = tool.equals("yt-dlp") ? "YT_DLP_PATH" : "FFMPEG_PATH";
            throw new JobFailure(tool + " isn't installed on the server (or isn't on the PATH). Install it, or set " + setting + ".", e);
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
                    throw new JobFailure(tool + " took longer than " + timeout.toMinutes() + " minutes and was stopped");
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
                        throw new JobFailure("ffmpeg stopped making progress" + (lastPosition >= 0 ? " at " + clock(lastPosition / 1000) : "")
                                + " for " + describe(stall) + " and was stopped");
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
