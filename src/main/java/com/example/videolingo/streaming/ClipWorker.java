package com.example.videolingo.streaming;

import com.example.videolingo.pipeline.PipelineProperties;
import com.example.videolingo.pipeline.PipelineSteps;
import com.example.videolingo.storage.MediaUrls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cuts the clips RuengFlix viewers share (up to 15 s of an episode): movie-api queues a PENDING row in
 * streaming_clips; this picks one at a time, cuts it with ffmpeg (720p H.264 + AAC, starts instantly
 * on phones), saves it and a poster frame to storage, and marks it READY (or FAILED with the reason).
 * A clip stuck WORKING for 10 minutes (the server restarted mid-cut) is tried again.
 * Off with streaming.clips.enabled=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipWorker {

    private final NamedParameterJdbcTemplate jdbc;
    private final MediaUrls mediaUrls;
    private final PipelineSteps steps;
    private final PipelineProperties props;

    @Value("${streaming.clips.enabled:true}")
    private boolean enabled;

    record Job(long id, String code, String videoUrl, int start, int seconds) {}

    @Scheduled(fixedDelayString = "${streaming.clips.poll-ms:3000}", initialDelay = 5000)
    public void runOne() {
        if (!enabled) return;
        Job job = claim();
        if (job == null) return;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("clip-" + job.code() + "-");
            Path mp4 = dir.resolve(job.code() + ".mp4");
            Path jpg = dir.resolve(job.code() + ".jpg");
            ffmpeg(cutArgs(props.ffmpeg(), mediaUrls.forServer(job.videoUrl()), job.start(), job.seconds(), mp4));
            ffmpeg(List.of(
                    props.ffmpeg(),
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-ss",
                    "0.5",
                    "-i",
                    mp4.toString(),
                    "-frames:v",
                    "1",
                    "-q:v",
                    "4",
                    jpg.toString()));
            String key = "clips/" + job.code() + ".mp4";
            String url = steps.uploadPublic(key, mp4, "video/mp4");
            String poster = steps.uploadPublic("clips/" + job.code() + ".jpg", jpg, "image/jpeg");
            jdbc.update(
                    "update streaming_clips set status = 'READY', url = :url, poster_url = :poster, storage_key = :key,"
                            + " finished_at = localtimestamp, error = null where id = :id",
                    Map.of("url", url, "poster", poster, "key", key, "id", job.id()));
            log.info("Clip {} ready ({} s from {} s)", job.code(), job.seconds(), job.start());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Clip {} failed: {}", job.code(), reason);
            jdbc.update(
                    "update streaming_clips set status = 'FAILED', error = :error, finished_at = localtimestamp"
                            + " where id = :id",
                    Map.of("error", reason.length() > 300 ? reason.substring(0, 300) : reason, "id", job.id()));
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Takes the oldest waiting clip (or one stuck mid-cut), so two servers never cut the same one */
    private Job claim() {
        List<Job> jobs = jdbc.query(
                """
                update streaming_clips k set status = 'WORKING', finished_at = localtimestamp
                from videos v
                where v.id = k.video_id and k.id = (
                    select id from streaming_clips
                    where status = 'PENDING' or (status = 'WORKING' and finished_at < localtimestamp - interval '10 minutes')
                    order by created_at limit 1 for update skip locked)
                returning k.id, k.code, v.video_url, k.start_seconds, k.seconds
                """,
                Map.of(),
                (rs, i) -> new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    /** The ffmpeg command for one clip: seek before the input (fast), re-encode for exact cuts and phones */
    static List<String> cutArgs(String ffmpeg, String input, int start, int seconds, Path out) {
        List<String> args = new ArrayList<>(List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                String.valueOf(start),
                "-i",
                input,
                "-t",
                String.valueOf(seconds),
                "-vf",
                "scale='min(1280,iw)':-2",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "26",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-b:a",
                "96k",
                "-movflags",
                "+faststart"));
        args.add(out.toString());
        return args;
    }

    private static void ffmpeg(List<String> args) throws IOException, InterruptedException {
        // Output goes to a file: reading the pipe would block past the timeout if ffmpeg hung
        Path log = Files.createTempFile("ffmpeg-", ".log");
        try {
            Process p = new ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took longer than 2 minutes");
            }
            if (p.exitValue() != 0) {
                String text = Files.readString(log).strip();
                throw new IOException(
                        "ffmpeg failed: " + (text.length() > 200 ? text.substring(text.length() - 200) : text));
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // Temp files: the OS cleans them eventually
        }
    }
}
