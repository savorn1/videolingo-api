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
        Path out = ctx.workDir().resolve("trim-" + startMs + "-" + (endMs == null ? "end" : endMs) + ".mp4");
        List<String> command = new ArrayList<>(List.of(props.ffmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", video, "-ss", millis(startMs)));
        if (endMs != null) {
            command.addAll(List.of("-to", millis(endMs)));
        }
        List<String> filters = new ArrayList<>();
        if (crop != null) {
            filters.add("crop=" + crop.w() + ":" + crop.h() + ":" + crop.x() + ":" + crop.y());
        }
        if (scale != null) {
            filters.add("scale=" + scale.w() + ":" + scale.h());
        }
        if (!filters.isEmpty()) {
            command.addAll(List.of("-vf", String.join(",", filters)));
        }
        command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", out.toString()));
        run(command, Duration.ofMinutes(60), ctx, "ffmpeg");
        return out;
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

    // Runs a tool, polling for cancellation; stdout+stderr go to a log file
    // (reading pipes instead could block the process when a buffer fills).
    private void run(List<String> command, Duration timeout, JobContext ctx, String tool) {
        Path logFile = ctx.workDir().resolve(tool + ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        } catch (IOException e) {
            String setting = tool.equals("yt-dlp") ? "YT_DLP_PATH" : "FFMPEG_PATH";
            throw new JobFailure(tool + " isn't installed on the server (or isn't on the PATH). Install it, or set " + setting + ".", e);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new JobFailure(tool + " took longer than " + timeout.toMinutes() + " minutes and was stopped");
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
