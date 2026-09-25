package com.example.videolingo.pipeline;

import com.example.videolingo.dto.TranscriptSegmentDto;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.Transcript;
import com.example.videolingo.entity.TranscriptSegment;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoDub;
import com.example.videolingo.entity.VideoExport;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.repository.SubtitleCueRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.TranscriptSegmentRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoExportRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.TranscriptService;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.subtitle.Cue;
import com.example.videolingo.subtitle.SubtitleFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// What each job type does. Steps take a JobContext for progress, so they can
// be chained: a DUB job transcribes and translates first when needed.
@Component
@RequiredArgsConstructor
public class PipelineSteps {

    static final String ACTOR = "processing-job";
    private static final int TTS_SAMPLE_RATE = 24_000;
    /** Speech may be sped up this much to fit its time slot. */
    private static final double MAX_SPEECH_RATE = 1.5;

    private final VideoRepository videoRepository;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptService transcriptService;
    private final VideoDubRepository dubRepository;
    private final VideoExportRepository exportRepository;
    private final SubtitleRepository subtitleRepository;
    private final SubtitleCueRepository cueRepository;
    private final SettingsService settings;
    private final MediaTools media;
    private final WhisperClient whisper;
    private final Translator translator;
    private final TextToSpeechClient tts;
    private final S3Client s3;
    private final ObjectMapper objectMapper;

    @Value("${s3.bucket:}")
    private String bucket;

    @Value("${s3.public-endpoint:}")
    private String publicEndpoint;

    // ── Job types ─────────────────────────────────────────────────────────

    // {"transcriptId": 1, "language": "en"}
    public void transcribeJob(ProcessingJob job, JobContext ctx) {
        Video video = video(job);
        String language = text(params(job), "language", video.getLanguage());
        if (language == null) {
            throw new JobFailure("The video has no spoken language set — set it before transcribing");
        }
        transcribe(video, language, job.getId(), ctx);
    }

    // {"transcriptId": 2, "sourceLanguage": "en", "targetLanguage": "km"}
    public void translateJob(ProcessingJob job, JobContext ctx) {
        Video video = video(job);
        JsonNode p = params(job);
        String from = text(p, "sourceLanguage", video.getLanguage());
        String to = text(p, "targetLanguage", null);
        if (from == null || to == null) {
            throw new JobFailure("The job is missing its source or target language");
        }
        translate(video, from, to, false, job.getId(), ctx);
    }

    // {"language": "km", "voice": "nova"}
    public void dubJob(ProcessingJob job, JobContext ctx) {
        Video video = video(job);
        JsonNode p = params(job);
        String language = text(p, "language", null);
        String voice = text(p, "voice", null);
        if (language == null || voice == null) {
            throw new JobFailure("The job is missing its language or voice");
        }
        tts.requireReady();
        String spoken = video.getLanguage();

        // 1. Text in the dub language: reuse it if there is some, else make it.
        Transcript target = withText(video.getId(), language);
        if (target != null) {
            ctx.info("Using the existing " + translator.name(language) + " transcript #" + target.getId());
        } else {
            if (spoken == null) {
                throw new JobFailure("The video has no spoken language set — set it before dubbing");
            }
            if (withText(video.getId(), spoken) == null) {
                ctx.info("No " + translator.name(spoken) + " transcript yet — transcribing the video first");
                transcribe(video, spoken, job.getId(), ctx.slice(0, 35));
            }
            if (!language.equalsIgnoreCase(spoken)) {
                translate(video, spoken, language, true, job.getId(), ctx.slice(35, 50));
            }
            target = withText(video.getId(), language);
            if (target == null) {
                throw new JobFailure("There's no " + translator.name(language) + " text to speak");
            }
        }

        // 2. Speak each line and place it on the timeline.
        List<TranscriptSegment> segments = segmentRepository.findByTranscriptIdOrderByPositionAsc(target.getId());
        JobContext speak = ctx.slice(50, 90);
        long videoMs = video.getDurationSeconds() != null ? video.getDurationSeconds() * 1000L : target.getDurationMs();
        WavMixer mixer = new WavMixer(TTS_SAMPLE_RATE, Math.max(videoMs, target.getDurationMs()));
        int spokenLines = 0;
        int squeezed = 0;
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            if (i % 5 == 0) {
                speak.progress(i * 100 / segments.size(), "Recording voice (" + (i + 1) + " of " + segments.size() + ")");
            }
            String line = s.getText().replace('\n', ' ').strip();
            if (line.isEmpty()) {
                continue;
            }
            // The line may use its own time plus any silence before the next one.
            long slot = i + 1 < segments.size()
                    ? Math.max(segments.get(i + 1).getStartMs() - s.getStartMs(), s.getEndMs() - s.getStartMs())
                    : Math.max(s.getEndMs() - s.getStartMs(), 1500);
            WavMixer.Clip clip = WavMixer.decode(tts.synthesize(line, voice, 1.0));
            if (clip.durationMs() > slot * 1.08) {
                double rate = Math.min(MAX_SPEECH_RATE, (double) clip.durationMs() / slot);
                clip = WavMixer.decode(tts.synthesize(line, voice, rate));
                squeezed++;
            }
            mixer.place(clip, s.getStartMs());
            spokenLines++;
        }
        if (spokenLines == 0) {
            throw new JobFailure("The " + translator.name(language) + " transcript has no text to speak");
        }
        if (squeezed > 0) {
            ctx.info(squeezed + " of " + spokenLines + " line(s) were spoken faster to fit their time");
        }

        // 3. Encode, store, and record it.
        ctx.progress(92, "Saving the audio track");
        Path wav = ctx.workDir().resolve("dub.wav");
        try {
            mixer.writeWav(wav);
        } catch (IOException e) {
            throw new JobFailure("Couldn't write the audio: " + e.getMessage(), e);
        }
        Path file = wav;
        String mime = "audio/wav";
        try {
            file = media.encodeMp3(wav, ctx);
            mime = "audio/mpeg";
        } catch (JobFailure e) {
            ctx.warn("Couldn't encode MP3 (" + e.getMessage() + ") — storing uncompressed WAV instead");
        }
        store(video, language, voice, target.getId(), file, mime, mixer.durationMs(), job.getId(), ctx);
    }

    /** How long a prepared download stays available. */
    static final int EXPORT_HOURS = 24;

    // {"mode": "FILE" | "IMPORT", "audio": "km" | null, "subtitleId": 7 | null}
    //   FILE   — an MP4 to save, kept for EXPORT_HOURS; with subtitleId, that
    //            track's cues are burned into the picture.
    //   IMPORT — the link video copied into our bucket; the video then plays
    //            from there (source UPLOAD, original link kept in importedFrom).
    public void downloadJob(ProcessingJob job, JobContext ctx) {
        Video video = video(job);
        JsonNode p = params(job);
        boolean importing = "IMPORT".equals(text(p, "mode", "FILE"));
        String audio = text(p, "audio", null);
        Long subtitleId = p.hasNonNull("subtitleId") ? p.get("subtitleId").asLong() : null;
        boolean isLink = isLink(video.getSource());
        long maxMb = settings.video().maxVideoUploadMb();

        // 1. The picture: download a link, or read our own file / the file URL.
        String input;
        if (isLink) {
            input = media.downloadVideo(video, maxMb, ctx.slice(0, 60)).toString();
        } else if (importing) {
            throw new JobFailure("This video is already a file — there's nothing to import");
        } else if (video.getStorageKey() != null) {
            ctx.progress(5, "Fetching the video file");
            input = fetch(video.getStorageKey(), "source-video", ctx).toString();
        } else {
            input = video.getVideoUrl();
        }

        // 2. The sound: keep it, or swap in a voice-over.
        String output = input;
        if (audio != null) {
            VideoDub dub = dubRepository.findByVideoIdAndLanguage(video.getId(), audio)
                    .orElseThrow(() -> new JobFailure("There's no " + translator.name(audio) + " voice-over for this video any more"));
            ctx.progress(65, "Adding the " + translator.name(audio) + " voice-over");
            Path dubFile = fetch(dub.getStorageKey(), "dub-audio", ctx);
            output = media.replaceAudio(input, dubFile, ctx).toString();
        }

        // 3. Subtitles drawn into the picture.
        String subtitleLabel = null;
        String subtitleLanguage = null;
        if (subtitleId != null && !importing) {
            Subtitle track = subtitleRepository.findById(subtitleId)
                    .filter(s -> s.getVideoId().equals(video.getId()))
                    .orElseThrow(() -> new JobFailure("That subtitle track no longer exists"));
            List<Cue> cues = cueRepository.findBySubtitleIdOrderByPositionAsc(track.getId()).stream()
                    .map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText())).toList();
            if (cues.isEmpty()) {
                throw new JobFailure("The subtitle track “" + track.getLabel() + "” has no cues");
            }
            ctx.progress(70, "Burning in the “" + track.getLabel() + "” subtitles");
            Path srt = ctx.workDir().resolve("burn.srt");
            try {
                Files.writeString(srt, SubtitleFiles.toSrt(cues), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new JobFailure("Couldn't write the subtitle file: " + e.getMessage(), e);
            }
            output = media.burnSubtitles(output, srt, ctx).toString();
            subtitleLabel = track.getLabel();
            subtitleLanguage = track.getLanguage();
        }
        Path file = Path.of(output);
        if (!Files.exists(file)) {
            // A remote file URL with no voice-over: nothing was made locally.
            throw new JobFailure("This video is a file link — download it directly from its URL");
        }

        // 4. Store it.
        ctx.progress(85, importing ? "Saving the video to storage" : "Preparing the download");
        requireBucket();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the video file: " + e.getMessage(), e);
        }
        if (size > maxMb * 1024 * 1024) {
            throw new JobFailure("The video is larger than the " + maxMb + " MB limit (Settings › Video)");
        }
        String fileName = fileName(video.getTitle(), audio, subtitleLanguage);
        if (importing) {
            String key = "videos/imported-" + video.getId() + "-" + job.getId() + ".mp4";
            upload(key, file, "video/mp4", null);
            ctx.checkpoint();
            importInto(video.getId(), key, size);
            ctx.info("Imported into storage (" + (size / (1024 * 1024)) + " MB) — the video now plays from your storage");
        } else {
            String key = "exports/" + video.getId() + "/" + job.getId() + ".mp4";
            upload(key, file, "video/mp4", fileName);
            exportRepository.save(VideoExport.builder()
                    .videoId(video.getId())
                    .jobId(job.getId())
                    .audioLanguage(audio)
                    .subtitleLabel(subtitleLabel)
                    .fileName(fileName)
                    .storageKey(key)
                    .url(publicUrl(key))
                    .sizeBytes(size)
                    .expiresAt(LocalDateTime.now().plusHours(EXPORT_HOURS))
                    .build());
            ctx.info("Download ready: " + fileName + " (" + (size / (1024 * 1024)) + " MB), available for " + EXPORT_HOURS + " hours");
        }
    }

    private void importInto(Long videoId, String key, long size) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new JobFailure("The video no longer exists"));
        String oldKey = video.getStorageKey();
        video.setImportedFrom(video.getVideoUrl());
        video.setSource(VideoSource.UPLOAD);
        video.setStorageKey(key);
        video.setVideoUrl(publicUrl(key));
        video.setFileSize(size);
        video.setMimeType("video/mp4");
        videoRepository.save(video);
        if (oldKey != null && !oldKey.equals(key)) {
            deleteObject(oldKey);
        }
    }

    static boolean isLink(VideoSource source) {
        return source == VideoSource.YOUTUBE || source == VideoSource.VIMEO || source == VideoSource.FACEBOOK;
    }

    static String fileName(String title, String audio) {
        return fileName(title, audio, null);
    }

    /**
     * "Lesson 1: Greetings" + km → "Lesson 1 Greetings (km).mp4" — safe on
     * every OS. Burned-in subtitles add "(en subs)" / "(km, en subs)".
     */
    static String fileName(String title, String audio, String subtitles) {
        String base = (title == null ? "video" : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (base.isEmpty()) {
            base = "video";
        }
        if (base.length() > 120) {
            base = base.substring(0, 120).strip();
        }
        String marks = audio != null && subtitles != null ? " (" + audio + ", " + subtitles + " subs)"
                : audio != null ? " (" + audio + ")"
                : subtitles != null ? " (" + subtitles + " subs)" : "";
        return base + marks + ".mp4";
    }

    // ── Steps ─────────────────────────────────────────────────────────────

    private void transcribe(Video video, String language, long jobId, JobContext ctx) {
        whisper.requireReady();
        if (video.getVideoUrl() == null || video.getVideoUrl().isBlank()) {
            throw new JobFailure("The video has no media link to transcribe");
        }
        Path audio = media.extractAudio(video, ctx.slice(0, 30));
        List<Path> chunks = media.split(audio, ctx);
        List<TranscriptSegmentDto> segments = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ctx.progress(30 + i * 65 / chunks.size(), chunks.size() > 1
                    ? "Transcribing (part " + (i + 1) + " of " + chunks.size() + ")"
                    : "Transcribing");
            long offset = (long) i * MediaTools.CHUNK_SECONDS * 1000;
            for (WhisperClient.Segment s : whisper.transcribe(chunks.get(i), language)) {
                segments.add(TranscriptSegmentDto.builder()
                        .startMs(offset + s.startMs())
                        .endMs(offset + s.endMs())
                        .text(s.text().length() > 2000 ? s.text().substring(0, 2000) : s.text())
                        .build());
            }
        }
        if (segments.isEmpty()) {
            throw new JobFailure("No speech was found in the audio");
        }
        ctx.progress(97, "Saving " + segments.size() + " segments");
        Long id = transcriptService.saveGenerated(video.getId(), language, segments, jobId, ACTOR);
        ctx.info("Transcribed " + segments.size() + " segments into transcript #" + id);
    }

    private void translate(Video video, String from, String to, boolean forSpeech, long jobId, JobContext ctx) {
        Transcript source = withText(video.getId(), from);
        if (source == null) {
            throw new JobFailure("There's no " + translator.name(from) + " transcript with text to translate from — transcribe the video first");
        }
        List<TranscriptSegment> rows = segmentRepository.findByTranscriptIdOrderByPositionAsc(source.getId());
        List<String> translated = translator.translate(rows.stream().map(TranscriptSegment::getText).toList(),
                from, to, forSpeech, video.getId(), source.getId(), ctx.slice(0, 95));
        List<TranscriptSegmentDto> segments = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String text = translated.get(i);
            segments.add(TranscriptSegmentDto.builder()
                    .startMs(rows.get(i).getStartMs())
                    .endMs(rows.get(i).getEndMs())
                    .text(text.length() > 2000 ? text.substring(0, 2000) : text)
                    .speaker(rows.get(i).getSpeaker())
                    .build());
        }
        ctx.progress(97, "Saving the translation");
        Long id = transcriptService.saveGenerated(video.getId(), to, segments, jobId, ACTOR);
        ctx.info("Translated " + segments.size() + " segments into " + translator.name(to) + " transcript #" + id);
    }

    private void store(Video video, String language, String voice, Long transcriptId, Path file, String mime,
                       long durationMs, long jobId, JobContext ctx) {
        requireBucket();
        String ext = mime.equals("audio/mpeg") ? "mp3" : "wav";
        String key = "dubs/" + video.getId() + "/" + language + "-" + jobId + "." + ext;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the audio track: " + e.getMessage(), e);
        }
        upload(key, file, mime, null);
        ctx.checkpoint();

        VideoDub dub = dubRepository.findByVideoIdAndLanguage(video.getId(), language).orElse(null);
        String oldKey = dub != null ? dub.getStorageKey() : null;
        if (dub == null) {
            dub = VideoDub.builder().videoId(video.getId()).language(language).createdBy(ACTOR).build();
        }
        dub.setVoice(voice);
        dub.setTranscriptId(transcriptId);
        dub.setStorageKey(key);
        dub.setAudioUrl(publicUrl(key));
        dub.setMimeType(mime);
        dub.setDurationMs(durationMs);
        dub.setSizeBytes(size);
        dub.setJobId(jobId);
        dubRepository.save(dub);
        if (oldKey != null && !oldKey.equals(key)) {
            deleteObject(oldKey);
        }
        ctx.info("Saved the " + translator.name(language) + " voice track (" + (size / 1024) + " KB)");
    }

    // ── storage ───────────────────────────────────────────────────────────

    private void requireBucket() {
        if (bucket == null || bucket.isBlank()) {
            throw new JobFailure("File storage isn't configured (S3_BUCKET)");
        }
    }

    private String publicUrl(String key) {
        return publicEndpoint.replaceAll("/+$", "") + "/" + bucket + "/" + key;
    }

    // `downloadName` makes browsers save the file under that name instead of playing it.
    private void upload(String key, Path file, String contentType, String downloadName) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (downloadName != null) {
            String ascii = downloadName.replaceAll("[^A-Za-z0-9 ._()-]", "_");
            req.contentDisposition("attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                    + URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        try {
            s3.putObject(req.build(), RequestBody.fromFile(file));
        } catch (RuntimeException e) {
            throw new JobFailure("Couldn't upload to storage: " + e.getMessage(), e);
        }
    }

    // Reads our own objects through the S3 API, not the public URL (which is
    // the browser's address for the bucket and may not resolve from here).
    private Path fetch(String key, String name, JobContext ctx) {
        requireBucket();
        String ext = key.contains(".") ? key.substring(key.lastIndexOf('.')) : "";
        Path out = ctx.workDir().resolve(name + ext);
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), out);
        } catch (RuntimeException e) {
            throw new JobFailure("Couldn't read " + key + " from storage: " + e.getMessage(), e);
        }
        return out;
    }

    void deleteObject(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ignored) {
            // An orphaned file costs a little storage; not worth failing over.
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Transcript withText(Long videoId, String language) {
        return transcriptRepository.findByVideoIdAndLanguage(videoId, language)
                .filter(t -> t.getSegmentCount() > 0)
                .orElse(null);
    }

    private Video video(ProcessingJob job) {
        Video video = videoRepository.findById(job.getVideoId())
                .orElseThrow(() -> new JobFailure("The video no longer exists"));
        if (video.isDeleted()) {
            throw new JobFailure("The video is in the trash — restore it first");
        }
        return video;
    }

    private JsonNode params(ProcessingJob job) {
        try {
            return job.getParameters() == null ? objectMapper.createObjectNode() : objectMapper.readTree(job.getParameters());
        } catch (IOException e) {
            throw new JobFailure("The job's parameters aren't valid JSON");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }
}
