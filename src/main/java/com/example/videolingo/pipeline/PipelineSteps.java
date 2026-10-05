package com.example.videolingo.pipeline;

import com.example.videolingo.dto.TranscriptSegmentDto;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.Transcript;
import com.example.videolingo.entity.TranscriptSegment;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoClip;
import com.example.videolingo.entity.VideoDub;
import com.example.videolingo.entity.VideoExport;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.repository.SubtitleCueRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.TranscriptSegmentRepository;
import com.example.videolingo.repository.VideoClipRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoExportRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.TranscriptService;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.storage.MediaUrls;
import com.example.videolingo.subtitle.Cue;
import com.example.videolingo.subtitle.SubtitleFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

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
    private final MediaUrls mediaUrls;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptService transcriptService;
    private final VideoDubRepository dubRepository;
    private final VideoExportRepository exportRepository;
    private final VideoClipRepository clipRepository;
    private final SubtitleRepository subtitleRepository;
    private final SubtitleCueRepository cueRepository;
    private final SettingsService settings;
    private final MediaTools media;
    private final WhisperClient whisper;
    private final Translator translator;
    private final TextToSpeechClient tts;
    private final S3Client s3;
    private final ObjectMapper objectMapper;
    private final PipelineProperties props;

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
                speak.progress(
                        i * 100 / segments.size(), "Recording voice (" + (i + 1) + " of " + segments.size() + ")");
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
            VideoDub dub = dubRepository
                    .findByVideoIdAndLanguage(video.getId(), audio)
                    .orElseThrow(() -> new JobFailure(
                            "There's no " + translator.name(audio) + " voice-over for this video any more"));
            ctx.progress(65, "Adding the " + translator.name(audio) + " voice-over");
            Path dubFile = fetch(dub.getStorageKey(), "dub-audio", ctx);
            output = media.replaceAudio(input, dubFile, ctx).toString();
        }

        // 3. Subtitles drawn into the picture.
        String subtitleLabel = null;
        String subtitleLanguage = null;
        if (subtitleId != null && !importing) {
            Subtitle track = subtitleRepository
                    .findById(subtitleId)
                    .filter(s -> s.getVideoId().equals(video.getId()))
                    .orElseThrow(() -> new JobFailure("That subtitle track no longer exists"));
            List<Cue> cues = cueRepository.findBySubtitleIdOrderByPositionAsc(track.getId()).stream()
                    .map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText()))
                    .toList();
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
            ctx.info("Imported into storage (" + (size / (1024 * 1024))
                    + " MB) — the video now plays from your storage");
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
            ctx.info("Download ready: " + fileName + " (" + (size / (1024 * 1024)) + " MB), available for "
                    + EXPORT_HOURS + " hours");
        }
    }

    /** How long an unpromoted clip stays available for review. */
    static final int CLIP_HOURS = 72;

    // {"operation":"TRIM","startMs":0,"endMs":5000,"crop":{"x":0,"y":0,"w":1280,"h":720},"scale":{"w":960,"h":540},"rotate":90,"flipH":true,"audio":{…AudioEditRules.Spec…},"padMs":3000,"look":{…VideoEditRules.Look…}}  (rotate/flips/audio/padMs/look optional; padMs = time past the video's end, held as the last frame; audio is added to the trimmed result)
    // {"operation":"SPLIT","segments":[{"startMs":0,"endMs":5000},{"startMs":5000,"endMs":9000}]}
    // {"operation":"CUT","cuts":[{"startMs":20000,"endMs":30000}]}  (merged, sorted; endMs null = to the end)
    // {"operation":"AUDIO","audio":{…AudioEditRules.Spec…},"summary":"Volume 150%, fade out 2 s"}
    // {"operation":"EXTRACT","format":"MP3" | "WAV"}
    // {"operation":"OVERLAY","overlay":{"layers":[…OverlayRules.Layer…]},"summary":"Text “Hello”, 1 image"}
    public void editJob(ProcessingJob job, JobContext ctx) {
        Video video = video(job);
        JsonNode p = params(job);
        String operation = text(p, "operation", null);
        if (operation == null) {
            throw new JobFailure("The job is missing its operation");
        }
        requireBucket();

        // Joins other videos into this one; there is no source video of its own to fetch.
        if ("MERGE".equals(operation)) {
            try {
                mergeJob(video, job, p, ctx);
            } catch (RuntimeException e) {
                discardUnfinished(video, ctx);
                throw e;
            }
            return;
        }

        // Makes the video's file from an uploaded sound; there is no source video to fetch.
        if ("AUDIO_TO_VIDEO".equals(operation)) {
            try {
                audioToVideoJob(video, job, p, ctx);
            } catch (RuntimeException e) {
                discardUnfinished(video, ctx);
                throw e;
            }
            return;
        }

        String input;
        if (video.getStorageKey() != null) {
            ctx.progress(5, "Fetching the video file");
            input = fetch(video.getStorageKey(), "source-video", ctx).toString();
            recordExactLength(video, input, ctx);
        } else if (isLink(video.getSource())) {
            throw new JobFailure("Import this video into storage first — link videos can't be edited directly");
        } else {
            input = video.getVideoUrl();
        }

        switch (operation) {
            case "SPLIT" -> splitJob(video, job, p, input, ctx);
            case "CUT" -> cutJob(video, job, p, input, ctx);
            case "AUDIO" -> audioJob(video, job, p, input, ctx);
            case "EXTRACT" -> extractJob(video, job, p, input, ctx);
            case "GIF" -> gifJob(video, job, p, input, ctx);
            case "STILL" -> stillJob(video, job, p, input, ctx);
            case "OVERLAY" -> overlayJob(video, job, p, input, ctx);
            default -> trimJob(video, job, p, input, ctx);
        }
    }

    // Older videos only have a rounded length; the file is on hand now, so the editor's limits get exact.
    private void recordExactLength(Video video, String input, JobContext ctx) {
        if (video.getDurationMs() != null) {
            return;
        }
        try {
            Long ms = media.probe(input, ctx.workDir()).durationMs();
            if (ms != null && ms > 0) {
                videoRepository.updateDurationMs(video.getId(), ms);
                video.setDurationMs(ms);
            }
        } catch (JobFailure e) {
            // The edit itself will say what's wrong with the file.
        }
    }

    // An uploaded sound (and optional cover picture) becomes the video's file.
    // The video row was created, disabled, when the request came in; this fills
    // it in, so the admin reviews it like any new upload before enabling it.
    /** How much of the sound a test render covers. */
    public static final long PREVIEW_MS = 5_000;
    /** At most this many test renders at once: they run in the request, so they must not crowd out the real jobs. */
    private final java.util.concurrent.Semaphore previewSlots = new java.util.concurrent.Semaphore(2);

    /**
     * A test render of a look (waveform style, colours, clean-up) with the real sound — the first few seconds, at
     * a small size, on the background colour — so it can be judged before the real video is made. Made on the spot;
     * the file is kept with the other temporary edit uploads and removed with them.
     */
    public String previewAudioToVideo(AudioToVideoRules.Spec spec) {
        String problem = AudioToVideoRules.validate(spec);
        if (problem != null) {
            throw new JobFailure(problem);
        }
        if (!spec.hasWaveform()) {
            throw new JobFailure("Choose a waveform to test");
        }
        if (!previewSlots.tryAcquire()) {
            throw new JobFailure("Other test renders are running — try again in a moment");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory(Path.of(props.workDirectory()), "preview-");
            JobContext ctx = new JobContext(null, 0, dir);
            Path audio = fetch(spec.audioKey(), "source-audio", ctx);
            MediaTools.Probe probe = media.probe(audio.toString(), dir);
            if (!probe.hasAudio() || probe.durationMs() == null || probe.durationMs() <= 0) {
                throw new JobFailure("That file has no audio in it, or its length can't be read");
            }
            AudioToVideoRules.Spec shown = new AudioToVideoRules.Spec(
                    spec.audioKey(),
                    null,
                    spec.background(),
                    "360p",
                    spec.waveform(),
                    spec.waveColor(),
                    false,
                    null,
                    spec.normalize(),
                    spec.denoise(),
                    List.of());
            Path out = media.audioToVideoQuick(shown, audio, Math.min(PREVIEW_MS, probe.durationMs()), dir);
            String key = OverlayRules.UPLOAD_PREFIX + "preview-" + java.util.UUID.randomUUID() + ".mp4";
            upload(key, out, "video/mp4", null);
            return publicUrl(key);
        } catch (IOException e) {
            throw new JobFailure("Couldn't prepare the test render: " + e.getMessage(), e);
        } finally {
            previewSlots.release();
            deleteDirQuietly(dir);
        }
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp space.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    private void audioToVideoJob(Video video, ProcessingJob job, JsonNode p, JobContext ctx) {
        List<AudioToVideoRules.Slide> requested = new ArrayList<>();
        p.path("slides")
                .forEach(n -> requested.add(new AudioToVideoRules.Slide(
                        n.path("key").asText(""), n.path("startMs").asLong(0))));
        AudioToVideoRules.Spec spec = new AudioToVideoRules.Spec(
                text(p, "audioKey", null),
                text(p, "coverKey", null),
                text(p, "background", null),
                text(p, "resolution", null),
                text(p, "waveform", null),
                text(p, "waveColor", null),
                p.path("titleCard").asBoolean(false),
                text(p, "titleText", null),
                p.path("normalize").asBoolean(false),
                p.path("denoise").asBoolean(false),
                requested);
        String problem = AudioToVideoRules.validate(spec);
        if (problem != null) {
            throw new JobFailure(problem);
        }
        ctx.progress(5, "Fetching the audio file");
        Path audio = fetch(spec.audioKey(), "source-audio", ctx);
        MediaTools.Probe probe = media.probe(audio.toString(), ctx.workDir());
        if (!probe.hasAudio()) {
            throw new JobFailure("That file has no audio in it");
        }
        if (probe.durationMs() == null || probe.durationMs() <= 0) {
            throw new JobFailure("Couldn't read how long the audio is");
        }
        // Only pictures that appear before the sound ends are fetched (the same choice the command makes).
        List<AudioToVideoRules.Slide> shown =
                spec.slides().isEmpty() ? List.of() : AudioToVideoRules.usableSlides(spec.slides(), probe.durationMs());
        if (shown.size() < spec.slides().size()) {
            ctx.info((spec.slides().size() - shown.size()) + " picture(s) start after the sound ends and are left out");
        }
        List<Path> covers = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            ctx.progress(
                    6 + i * 6 / shown.size(),
                    shown.size() > 1
                            ? "Fetching picture " + (i + 1) + " of " + shown.size()
                            : "Fetching the cover picture");
            covers.add(fetch(shown.get(i).key(), "cover-" + i, ctx));
        }
        Path cover = covers.isEmpty() ? null : covers.get(0);
        AudioToVideoRules.Size frame = AudioToVideoRules.size(spec.resolution());

        // The title, drawn like a text layer of the editor, wrapped to fit the frame.
        Path titlePng = null;
        if (spec.drawsTitle()) {
            ctx.progress(12, "Drawing the title");
            int fontPx = Math.max(6, (int) Math.round(frame.h() * 0.07));
            int maxChars = Math.max(8, (int) (frame.w() * 0.8 / (fontPx * 0.55)));
            String wrapped = String.join("\n", AudioToVideoRules.wrapTitle(spec.titleText(), maxChars, 4));
            OverlayRules.Layer layer = new OverlayRules.Layer(
                    "TEXT",
                    wrapped,
                    "SansSerif",
                    700,
                    7.0,
                    AudioToVideoRules.contrastColor(spec.background()),
                    null,
                    0.0,
                    "CENTER",
                    null,
                    0.0,
                    0.5,
                    0.5,
                    1.0,
                    0L,
                    null,
                    "NONE");
            titlePng = ctx.workDir().resolve("title.png");
            TextRenderer.write(TextRenderer.render(layer, frame.h()), titlePng);
        }

        ctx.progress(15, "Making the video");
        Path out = media.audioToVideo(spec, audio, covers, titlePng, frame, probe.durationMs(), ctx.slice(15, 92));
        ctx.progress(93, "Saving the video");
        String key = "videos/" + java.util.UUID.randomUUID() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();

        // The cover doubles as the thumbnail, kept under its own name because the edit upload is temporary.
        if (cover != null) {
            String name = cover.getFileName().toString().toLowerCase(Locale.ROOT);
            String ext = name.endsWith(".png") ? "png" : name.endsWith(".webp") ? "webp" : "jpg";
            String thumbKey = "thumbnails/" + java.util.UUID.randomUUID() + "." + ext;
            upload(thumbKey, cover, ext.equals("jpg") ? "image/jpeg" : "image/" + ext, null);
            video.setThumbnailUrl(publicUrl(thumbKey));
        }
        video.setStorageKey(key);
        video.setVideoUrl(publicUrl(key));
        video.setSource(VideoSource.UPLOAD);
        video.setMimeType("video/mp4");
        video.setFileSize(size(out));
        video.setDurationSeconds(seconds(probe.durationMs()));
        video.setDurationMs(probe.durationMs());
        video.setWidth(frame.w());
        video.setHeight(frame.h());
        videoRepository.save(video);
        ctx.info("Video ready — review it, then enable it for learners");

        // The video is already made, so a transcript that can't be made must not fail the job.
        if (p.path("transcribe").asBoolean(false)) {
            if (video.getLanguage() == null) {
                ctx.warn(
                        "No spoken language is set, so no transcript was made. Set it and transcribe from the video's page.");
            } else {
                try {
                    transcribe(video, video.getLanguage(), job.getId(), ctx.slice(94, 100));
                } catch (JobFailure e) {
                    ctx.warn("The video is ready, but its transcript could not be made: " + e.getMessage());
                }
            }
        }
    }

    // Several stored videos become one. The video row was created, hidden, when the request
    // came in; this fills it in, and the originals are left as they are.
    private void mergeJob(Video video, ProcessingJob job, JsonNode p, JobContext ctx) {
        List<Long> ids = new ArrayList<>();
        p.path("videoIds").forEach(n -> ids.add(n.asLong()));
        String resolution = text(p, "resolution", null);
        String transition = text(p, "transition", null);
        String problem = MergeRules.validate(ids, resolution, transition);
        if (problem != null) {
            throw new JobFailure(problem);
        }

        List<Path> files = new ArrayList<>();
        List<MergeRules.Part> parts = new ArrayList<>();
        List<Video> sources = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Video source = videoRepository
                    .findById(ids.get(i))
                    .orElseThrow(() -> new JobFailure("A video to join no longer exists"));
            sources.add(source);
            if (source.isDeleted()) {
                throw new JobFailure("\"" + source.getTitle() + "\" is in the trash — restore it or leave it out");
            }
            if (source.getStorageKey() == null) {
                throw new JobFailure("\"" + source.getTitle() + "\" is a link, not a stored file — import it first");
            }
            ctx.progress(3 + i * 20 / ids.size(), "Fetching video " + (i + 1) + " of " + ids.size());
            Path file = fetch(source.getStorageKey(), "part-" + i, ctx);
            MediaTools.Probe probe = media.probe(file.toString(), ctx.workDir());
            if (!probe.hasVideo()) {
                throw new JobFailure("\"" + source.getTitle() + "\" has no picture to join");
            }
            files.add(file);
            parts.add(new MergeRules.Part(probe.durationMs() == null ? 0 : probe.durationMs(), probe.hasAudio()));
        }
        problem = MergeRules.validateParts(parts);
        if (problem != null) {
            throw new JobFailure(problem);
        }
        AudioToVideoRules.Size frame = AudioToVideoRules.size(resolution);
        long totalMs = MergeRules.totalMs(parts, transition);
        long silent = parts.stream().filter(x -> !x.hasAudio()).count();
        if (silent > 0) {
            ctx.info(silent + " of the videos have no sound; silence is used for them");
        }

        ctx.progress(25, "Joining the videos");
        Path out = media.merge(files, parts, frame, transition, ctx.slice(25, 90));
        ctx.progress(92, "Saving the video");
        String key = "videos/" + java.util.UUID.randomUUID() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();

        // A frame from the start makes the thumbnail; the video is fine without one if it can't be taken.
        try {
            Path frameFile = media.frame(out, Math.min(1000, totalMs / 2), ctx);
            String thumbKey = "thumbnails/" + java.util.UUID.randomUUID() + ".jpg";
            upload(thumbKey, frameFile, "image/jpeg", null);
            video.setThumbnailUrl(publicUrl(thumbKey));
        } catch (JobFailure e) {
            ctx.warn("The thumbnail could not be made: " + e.getMessage());
        }
        video.setStorageKey(key);
        video.setVideoUrl(publicUrl(key));
        video.setSource(VideoSource.UPLOAD);
        video.setMimeType("video/mp4");
        video.setFileSize(size(out));
        video.setDurationSeconds(seconds(totalMs));
        video.setDurationMs(totalMs);
        video.setWidth(frame.w());
        video.setHeight(frame.h());
        videoRepository.save(video);
        ctx.info("Video ready — review it, then enable it for learners");
        carryTranscripts(video, sources, parts, job, ctx);
    }

    // Transcripts the joined videos all have come along, each clip's text moved to where the clip now sits.
    // Only languages every video has: a video without one would leave a hole. The video is already made,
    // so nothing here may fail the job.
    private void carryTranscripts(
            Video video, List<Video> sources, List<MergeRules.Part> parts, ProcessingJob job, JobContext ctx) {
        try {
            List<java.util.Map<String, Transcript>> perVideo = new ArrayList<>();
            for (Video source : sources) {
                java.util.Map<String, Transcript> byLanguage = new java.util.LinkedHashMap<>();
                for (Transcript t : transcriptRepository.findByVideoId(source.getId())) {
                    if (t.getSegmentCount() > 0) {
                        byLanguage.put(t.getLanguage(), t);
                    }
                }
                perVideo.add(byLanguage);
            }
            List<String> languages = MergeRules.commonLanguages(
                    perVideo.stream().map(m -> (Set<String>) m.keySet()).toList());
            if (languages.isEmpty()) {
                return;
            }
            List<Long> offsets = MergeRules.offsets(parts);
            for (String language : languages) {
                List<TranscriptSegmentDto> merged = new ArrayList<>();
                for (int i = 0; i < sources.size(); i++) {
                    List<MergeRules.Seg> segs =
                            segmentRepository
                                    .findByTranscriptIdOrderByPositionAsc(
                                            perVideo.get(i).get(language).getId())
                                    .stream()
                                    .map(x -> new MergeRules.Seg(
                                            x.getStartMs(), x.getEndMs(), x.getText(), x.getSpeaker()))
                                    .toList();
                    for (MergeRules.Seg s :
                            MergeRules.shift(segs, offsets.get(i), parts.get(i).durationMs())) {
                        merged.add(TranscriptSegmentDto.builder()
                                .startMs(s.startMs())
                                .endMs(s.endMs())
                                .text(s.text())
                                .speaker(s.speaker())
                                .build());
                    }
                }
                if (!merged.isEmpty()) {
                    transcriptService.saveGenerated(video.getId(), language, merged, job.getId(), ACTOR);
                }
            }
            ctx.info("Carried over the " + String.join(", ", languages) + " transcript"
                    + (languages.size() == 1 ? "" : "s") + " of the joined videos");
        } catch (RuntimeException e) {
            ctx.warn("The transcripts could not be carried over: " + e.getMessage());
        }
    }

    // A video made from audio starts as a hidden placeholder; when the job fails or is cancelled
    // before it has a file of its own, that placeholder would only point at a temporary upload.
    // Move it to the trash (it can be restored) rather than leave it lying around.
    private void discardUnfinished(Video video, JobContext ctx) {
        if (video.getStorageKey() != null || video.isDeleted()) {
            return;
        }
        try {
            video.setDeletedAt(LocalDateTime.now());
            videoRepository.save(video);
            ctx.warn("The unfinished video was moved to the trash. Restore it to try again.");
        } catch (RuntimeException e) {
            // Best effort: the job's own failure is what matters.
        }
    }

    /** What an audio render made: the file and how long it is after any speed change. */
    private record AudioRender(Path file, long outMs) {}

    /** Renders `spec`'s sound onto `input` (the shared part of an AUDIO job and a trim with added audio). */
    private AudioRender renderAudio(AudioEditRules.Spec spec, String input, JobContext ctx) {
        ctx.progress(10, "Reading the video");
        MediaTools.Probe source = media.probe(input, ctx.workDir());
        if (source.durationMs() == null || source.durationMs() <= 0) {
            throw new JobFailure("Couldn't read how long the video is");
        }

        Path replacement = null;
        MediaTools.Probe replacementProbe = null;
        if (spec.replaceKey() != null) {
            ctx.progress(15, "Fetching the replacement audio");
            replacement = fetch(spec.replaceKey(), "replacement-audio", ctx);
            replacementProbe = media.probe(replacement.toString(), ctx.workDir());
            if (!replacementProbe.hasAudio()) {
                throw new JobFailure("The replacement file has no audio in it");
            }
        }
        Path music = null;
        if (spec.music() != null) {
            ctx.progress(20, "Fetching the background music");
            music = fetch(spec.music().key(), "music", ctx);
            if (!media.probe(music.toString(), ctx.workDir()).hasAudio()) {
                throw new JobFailure("The background music file has no audio in it");
            }
        }

        boolean mono = replacementProbe != null ? replacementProbe.mono() : source.mono();
        AudioEditRules.Inputs inputs = new AudioEditRules.Inputs(
                source.durationMs(),
                source.hasAudio(),
                mono,
                replacementProbe != null ? replacementProbe.durationMs() : source.durationMs());
        AudioEditRules.Graph graph = AudioEditRules.build(spec, inputs);
        long outMs = Math.round(source.durationMs() / spec.speed());

        ctx.progress(25, graph.picture() ? "Rendering the sound and re-timing the picture" : "Rendering the sound");
        Path out = media.editAudio(
                input,
                replacement,
                music,
                spec.music() != null && spec.music().loop(),
                graph,
                outMs,
                ctx.slice(25, 90));
        return new AudioRender(out, outMs);
    }

    private void audioJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        AudioEditRules.Spec spec;
        try {
            spec = jobParams().treeToValue(p.get("audio"), AudioEditRules.Spec.class);
        } catch (Exception e) {
            throw new JobFailure("The job's audio settings aren't valid: " + e.getMessage());
        }
        if (spec == null) {
            throw new JobFailure("The job has no audio settings");
        }
        AudioRender rendered = renderAudio(spec, input, ctx);
        Path out = rendered.file();
        long outMs = rendered.outMs();
        ctx.progress(92, "Saving the result");
        String key = "edits/" + video.getId() + "/" + job.getId() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.AUDIO)
                .startMs(0)
                .endMs(null)
                .durationMs(outMs)
                .durationSeconds(seconds(outMs))
                .width(video.getWidth())
                .height(video.getHeight())
                .summary(text(p, "summary", AudioEditRules.describe(spec)))
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Audio edit ready — listen to it, then replace the original video or discard it");
    }

    private void overlayJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        OverlayRules.Spec spec;
        try {
            spec = jobParams().treeToValue(p.get("overlay"), OverlayRules.Spec.class);
        } catch (Exception e) {
            throw new JobFailure("The job's layers aren't valid: " + e.getMessage());
        }
        if (spec == null || (spec.layers().isEmpty() && spec.captions() == null)) {
            throw new JobFailure("The job has no layers");
        }
        ctx.progress(5, "Reading the video");
        MediaTools.Probe probe = media.probe(input, ctx.workDir());
        if (!probe.hasVideo() || probe.width() == null || probe.height() == null) {
            throw new JobFailure("Couldn't read the video's picture size");
        }
        if (probe.durationMs() == null || probe.durationMs() <= 0) {
            throw new JobFailure("Couldn't read how long the video is");
        }

        boolean captions = spec.captions() != null;
        Path out = Path.of(input);
        if (!spec.layers().isEmpty()) {
            ctx.progress(10, "Preparing the layers");
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < spec.layers().size(); i++) {
                OverlayRules.Layer layer = spec.layers().get(i);
                if (layer.textual()) {
                    Path png = ctx.workDir().resolve("layer-" + i + ".png");
                    TextRenderer.write(TextRenderer.render(layer, probe.height()), png);
                    files.add(png);
                } else {
                    files.add(fetch(layer.imageKey(), "layer-" + i, ctx));
                }
            }
            OverlayRules.Graph graph = OverlayRules.build(spec, probe.width(), probe.durationMs());

            ctx.progress(20, "Drawing the layers onto the video");
            out = media.overlay(input, files, graph, probe.durationMs(), ctx.slice(20, captions ? 60 : 92));
        }
        if (captions) {
            ctx.progress(captions && spec.layers().isEmpty() ? 20 : 60, "Burning in the subtitles");
            Path ass = ctx.workDir().resolve("captions.ass");
            try {
                Files.writeString(ass, captionFile(spec.captions(), probe.width(), probe.height()));
            } catch (IOException e) {
                throw new JobFailure("Couldn't write the subtitles: " + e.getMessage());
            }
            out = media.burnSubtitles(out.toString(), ass, ctx);
        }
        ctx.progress(93, "Saving the result");
        String key = "edits/" + video.getId() + "/" + job.getId() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.OVERLAY)
                .startMs(0)
                .endMs(null)
                .durationMs(probe.durationMs())
                .durationSeconds(seconds(probe.durationMs()))
                .width(probe.width())
                .height(probe.height())
                .summary(text(p, "summary", OverlayRules.describe(spec)))
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Text & overlay ready — preview it, then replace the original video or discard it");
    }

    /** The ASS file for burned-in subtitles: the transcript's lines, with the second transcript's under them. */
    private String captionFile(OverlayRules.Captions c, int width, int height) {
        List<CaptionRules.Line> first = captionLines(c.transcriptId());
        if (first.isEmpty()) {
            throw new JobFailure("The transcript for the subtitles has no lines");
        }
        List<CaptionRules.Line> second =
                c.secondTranscriptId() != null ? captionLines(c.secondTranscriptId()) : List.of();
        return CaptionRules.toAss(
                CaptionRules.pair(first, second), c.style(), c.position(), c.sizePct(), width, height);
    }

    private List<CaptionRules.Line> captionLines(Long transcriptId) {
        return segmentRepository.findByTranscriptIdOrderByPositionAsc(transcriptId).stream()
                .map(s -> new CaptionRules.Line(s.getStartMs(), s.getEndMs(), s.getText()))
                .toList();
    }

    private void extractJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        boolean wav = "WAV".equals(text(p, "format", "MP3"));
        ctx.progress(20, "Extracting the audio");
        Path out = media.exportAudio(input, wav ? "WAV" : "MP3", ctx.slice(20, 90));
        ctx.progress(92, "Saving the audio file");
        String ext = wav ? "wav" : "mp3";
        String key = "edits/" + video.getId() + "/" + job.getId() + "-audio." + ext;
        String downloadName = fileName(video.getTitle(), null).replaceAll("\\.mp4$", "." + ext);
        upload(key, out, wav ? "audio/wav" : "audio/mpeg", downloadName);
        ctx.checkpoint();
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.EXTRACT)
                .startMs(0)
                .endMs(null)
                .durationMs(durationMs(video))
                .durationSeconds(video.getDurationSeconds())
                .summary(wav ? "WAV · 16-bit PCM" : "MP3 · 192 kbps")
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Audio extracted as " + downloadName);
    }

    /** The clip with its intro and/or outro card, all at the clip's size; silent cards when the clip has sound. */
    private Path withCards(Path clip, VideoEditRules.Cards cards, JobContext ctx) {
        MediaTools.Probe info = media.probe(clip.toString(), ctx.workDir());
        if (info.width() == null || info.height() == null) {
            throw new JobFailure("Couldn't read the clip's picture size for the cards");
        }
        int w = info.width();
        int h = info.height();
        java.awt.image.BufferedImage logo = null;
        if (cards.logoKey() != null) {
            try {
                logo = javax.imageio.ImageIO.read(
                        fetch(cards.logoKey(), "card-logo", ctx).toFile());
            } catch (IOException e) {
                throw new JobFailure("Couldn't read the logo: " + e.getMessage());
            }
        }
        List<String> parts = new ArrayList<>();
        if (cards.intro() != null) {
            parts.add(cardVideo(cards.intro(), cards, logo, w, h, info.hasAudio(), "intro", ctx)
                    .toString());
        }
        parts.add(clip.toString());
        if (cards.outro() != null) {
            parts.add(cardVideo(cards.outro(), cards, logo, w, h, info.hasAudio(), "outro", ctx)
                    .toString());
        }
        return media.join(parts, w, h, info.hasAudio(), ctx);
    }

    private Path cardVideo(
            VideoEditRules.Card card,
            VideoEditRules.Cards cards,
            java.awt.image.BufferedImage logo,
            int w,
            int h,
            boolean sound,
            String name,
            JobContext ctx) {
        java.awt.image.BufferedImage text = TextRenderer.render(CardRenderer.textLayer(card.text(), cards.color()), h);
        Path png = ctx.workDir().resolve(name + "-card.png");
        TextRenderer.write(CardRenderer.compose(text, logo, w, h), png);
        return media.card(png, cards.background(), w, h, card.durationMs(), sound, name, ctx);
    }

    private void gifJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        long startMs = p.path("startMs").asLong(0);
        long endMs = p.path("endMs").asLong(startMs + 3000);
        int width = p.path("width").asInt(480);
        ctx.progress(20, "Making the GIF");
        Path out = media.gif(input, startMs, endMs, width, VideoEditRules.GIF_FPS, ctx.slice(20, 90));
        ctx.progress(92, "Saving the GIF");
        String key = "edits/" + video.getId() + "/" + job.getId() + ".gif";
        String downloadName = fileName(video.getTitle(), null).replaceAll("\\.mp4$", ".gif");
        upload(key, out, "image/gif", downloadName);
        ctx.checkpoint();
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.GIF)
                .startMs(startMs)
                .endMs(endMs)
                .durationMs(endMs - startMs)
                .durationSeconds(seconds(endMs - startMs))
                .width(width)
                .summary("GIF · " + width + " px wide · " + VideoEditRules.GIF_FPS + " fps")
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("GIF ready as " + downloadName);
    }

    private void stillJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        long atMs = p.path("atMs").asLong(0);
        ctx.progress(30, "Taking the picture");
        Path out = media.still(input, atMs, ctx.slice(30, 90));
        ctx.progress(92, "Saving the picture");
        String key = "edits/" + video.getId() + "/" + job.getId() + ".jpg";
        String downloadName = fileName(video.getTitle(), null).replaceAll("\\.mp4$", ".jpg");
        upload(key, out, "image/jpeg", downloadName);
        ctx.checkpoint();
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.STILL)
                .startMs(atMs)
                .endMs(null)
                .summary("JPG picture")
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Picture ready as " + downloadName);
    }

    private void trimJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        EditJobParams.Trim t = read(p, EditJobParams.Trim.class, "trim settings");
        long startMs = t.startMs();
        Long endMs = t.endMs();
        MediaTools.CropRect crop = t.crop();
        MediaTools.ScaleSize scale = t.scale();
        Integer rotate = t.rotate();
        boolean flipH = Boolean.TRUE.equals(t.flipH());
        boolean flipV = Boolean.TRUE.equals(t.flipV());
        long padMs = t.padMs() == null ? 0 : t.padMs();
        VideoEditRules.Look look = t.look();
        String effect = t.effect();
        VideoEditRules.Fade fade = t.fade();

        ctx.progress(20, "Trimming");
        Path out = media.trim(
                input,
                startMs,
                endMs,
                crop,
                scale,
                rotate,
                flipH,
                flipV,
                padMs,
                look,
                effect,
                fade,
                t.blurs() == null ? List.of() : t.blurs(),
                ctx.slice(20, t.audio() != null ? 60 : 90));
        // Then, in order: a part at another speed, a held frame, a video in a corner (times on the trimmed clip as the
        // editor shows it — the freeze is moved along by the speed change), the added audio, and last the cards.
        if (t.speed() != null) {
            ctx.progress(55, "Changing the speed");
            MediaTools.Probe info = media.probe(out.toString(), ctx.workDir());
            long clipLength =
                    info.durationMs() != null ? info.durationMs() : t.speed().endMs();
            out = media.speedRange(out.toString(), t.speed(), info.hasAudio(), clipLength, ctx);
        }
        if (t.freeze() != null) {
            ctx.progress(t.audio() != null ? 58 : 88, "Holding the frame");
            boolean sound = media.probe(out.toString(), ctx.workDir()).hasAudio();
            long at = VideoEditRules.rampedMs(t.freeze().atMs(), t.speed());
            out = media.freeze(out.toString(), at, t.freeze().durationMs(), sound, ctx);
        }
        if (t.pip() != null) {
            ctx.progress(60, "Adding the picture in picture");
            Video other = videoRepository
                    .findById(t.pip().videoId())
                    .orElseThrow(() -> new JobFailure("The video for the picture in picture is gone"));
            if (other.getStorageKey() == null) {
                throw new JobFailure("The video for the picture in picture isn't a stored file");
            }
            Path second = fetch(other.getStorageKey(), "pip-source", ctx);
            MediaTools.Probe info = media.probe(out.toString(), ctx.workDir());
            if (info.width() == null) {
                throw new JobFailure("Couldn't read the clip's picture size");
            }
            out = media.pip(
                    out.toString(), second.toString(), t.pip().corner(), t.pip().sizePct(), info.width(), ctx);
        }
        String audioSummary = null;
        if (t.audio() != null) {
            ctx.progress(60, "Adding the audio");
            out = renderAudio(t.audio(), out.toString(), ctx.slice(60, 90)).file();
        }
        if (t.cards() != null) {
            ctx.progress(90, "Adding the cards");
            out = withCards(out, t.cards(), ctx);
            audioSummary = AudioEditRules.describe(t.audio());
        }
        ctx.progress(92, "Saving the clip");
        Long outMs = outputMs(out, endMs != null ? endMs - startMs : null, ctx);
        String key = "edits/" + video.getId() + "/" + job.getId() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();

        // Boxed on every branch on purpose: mixing a primitive int (crop.w()/scale.w())
        // with an Integer (video.getWidth()) in a ternary makes Java auto-unbox the
        // Integer branch, which NPEs when the video has no recorded width/height.
        // A quarter turn swaps the sides of the picture that was cropped (or the whole one); a resize is already the
        // final size.
        boolean swap = VideoEditRules.swapsSides(rotate);
        Integer baseW = crop != null ? Integer.valueOf(crop.w()) : video.getWidth();
        Integer baseH = crop != null ? Integer.valueOf(crop.h()) : video.getHeight();
        Integer width = scale != null ? Integer.valueOf(scale.w()) : swap ? baseH : baseW;
        Integer height = scale != null ? Integer.valueOf(scale.h()) : swap ? baseW : baseH;
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.TRIM)
                .startMs(startMs)
                .endMs(endMs)
                .cropX(crop != null ? crop.x() : null)
                .cropY(crop != null ? crop.y() : null)
                .cropW(crop != null ? crop.w() : null)
                .cropH(crop != null ? crop.h() : null)
                .scaleW(scale != null ? scale.w() : null)
                .scaleH(scale != null ? scale.h() : null)
                .durationMs(outMs)
                .durationSeconds(seconds(outMs))
                .width(width)
                .height(height)
                .summary(joinSummary(
                        joinSummary(
                                joinSummary(
                                        VideoEditRules.describeOrientation(rotate, flipH, flipV),
                                        VideoEditRules.describeLook(look)),
                                joinSummary(
                                        joinSummary(
                                                VideoEditRules.describeEffect(effect),
                                                VideoEditRules.describeFade(fade)),
                                        VideoEditRules.describeFreeze(t.freeze()))),
                        audioSummary))
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Trim ready — review it, then replace the original video or discard it");
    }

    private static String joinSummary(String a, String b) {
        return a == null ? b : b == null ? a : a + "; " + b;
    }

    private void cutJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        EditJobParams.Cut params = read(p, EditJobParams.Cut.class, "cut settings");
        List<VideoEditRules.Segment> cuts = params.cuts() == null
                ? List.of()
                : params.cuts().stream()
                        .map(c -> new VideoEditRules.Segment(c.startMs(), c.endMs()))
                        .toList();
        if (cuts.isEmpty()) {
            throw new JobFailure("The job has no cuts");
        }
        MediaTools.Probe probe = media.probe(input, ctx.workDir());
        Long totalMs = probe.durationMs() != null ? probe.durationMs() : durationMs(video);
        long removedMs = 0;
        for (VideoEditRules.Segment c : cuts) {
            Long end = c.endMs() != null ? c.endMs() : totalMs;
            removedMs += end == null ? 0 : Math.max(0, Math.min(end, totalMs != null ? totalMs : end) - c.startMs());
        }
        Long keptMs = totalMs == null ? null : Math.max(0, totalMs - removedMs);

        ctx.progress(20, "Cutting out " + cuts.size() + " range(s)");
        Path out = media.cut(input, cuts, probe.hasAudio(), keptMs, ctx.slice(20, 90));
        Long outMs = outputMs(out, keptMs, ctx);
        ctx.progress(92, "Saving the clip");
        String key = "edits/" + video.getId() + "/" + job.getId() + ".mp4";
        upload(key, out, "video/mp4", null);
        ctx.checkpoint();

        // Stored as a TRIM result, so it can replace the original or become a new video like any trim.
        clipRepository.save(VideoClip.builder()
                .videoId(video.getId())
                .jobId(job.getId())
                .operation(VideoClip.Operation.TRIM)
                .startMs(0)
                .endMs(null)
                .durationMs(outMs)
                .durationSeconds(seconds(outMs))
                .width(video.getWidth())
                .height(video.getHeight())
                .summary("Cut out " + cuts.size() + (cuts.size() == 1 ? " range" : " ranges")
                        + (removedMs > 0 ? " (" + removedMs / 1000 + " s)" : ""))
                .storageKey(key)
                .sourceKey(video.getStorageKey())
                .url(publicUrl(key))
                .sizeBytes(size(out))
                .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                .build());
        ctx.info("Cut ready — review it, then replace the original video or add it as a new one");
    }

    private void splitJob(Video video, ProcessingJob job, JsonNode p, String input, JobContext ctx) {
        EditJobParams.Split params = read(p, EditJobParams.Split.class, "split settings");
        List<EditJobParams.Range> segments = params.segments() == null ? List.of() : params.segments();
        if (segments.isEmpty()) {
            throw new JobFailure("The job has no segments");
        }
        int n = segments.size();
        for (int i = 0; i < n; i++) {
            long startMs = segments.get(i).startMs();
            Long endMs = segments.get(i).endMs();
            JobContext slice = ctx.slice(i * 100 / n, (i + 1) * 100 / n);
            slice.progress(5, "Cutting segment " + (i + 1) + " of " + n);
            Path out = media.trim(input, startMs, endMs, null, null, slice.slice(5, 90));
            Long outMs = outputMs(out, endMs != null ? endMs - startMs : null, ctx);
            String key = "edits/" + video.getId() + "/" + job.getId() + "-" + i + ".mp4";
            upload(key, out, "video/mp4", null);
            ctx.checkpoint();
            clipRepository.save(VideoClip.builder()
                    .videoId(video.getId())
                    .jobId(job.getId())
                    .operation(VideoClip.Operation.SPLIT)
                    .segmentIndex(i)
                    .startMs(startMs)
                    .endMs(endMs)
                    .durationMs(outMs)
                    .durationSeconds(seconds(outMs))
                    .width(video.getWidth())
                    .height(video.getHeight())
                    .storageKey(key)
                    .sourceKey(video.getStorageKey())
                    .url(publicUrl(key))
                    .sizeBytes(size(out))
                    .expiresAt(LocalDateTime.now().plusHours(CLIP_HOURS))
                    .build());
        }
        ctx.info("Split into " + n + " segment(s) — review them, then add any as a new video or discard them");
    }

    /** The video's exact length, or its rounded one for a video not re-read since exact lengths were recorded. */
    static Long durationMs(Video video) {
        if (video.getDurationMs() != null) {
            return video.getDurationMs();
        }
        return video.getDurationSeconds() == null ? null : video.getDurationSeconds() * 1000L;
    }

    /** The whole seconds shown for a length (rounded, at least 1), as videos have always been recorded. */
    static Integer seconds(Long ms) {
        return ms == null ? null : (int) Math.max(1, Math.round(ms / 1000.0));
    }

    /** How long a finished file actually plays; `expected` when it can't be read. */
    private Long outputMs(Path out, Long expected, JobContext ctx) {
        try {
            Long ms = media.probe(out.toString(), ctx.workDir()).durationMs();
            return ms != null ? ms : expected;
        } catch (JobFailure e) {
            return expected;
        }
    }

    private volatile ObjectMapper jobParams;

    /** Reads job parameters, skipping fields this build doesn't know (see EditJobParams.reader). */
    private ObjectMapper jobParams() {
        ObjectMapper m = jobParams;
        if (m == null) {
            m = EditJobParams.reader(objectMapper);
            jobParams = m;
        }
        return m;
    }

    private <T> T read(JsonNode params, Class<T> type, String what) {
        try {
            return jobParams().treeToValue(params, type);
        } catch (Exception e) {
            throw new JobFailure("The job's " + what + " aren't valid: " + e.getMessage());
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the clip file: " + e.getMessage(), e);
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
        String base = (title == null ? "video" : title)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (base.isEmpty()) {
            base = "video";
        }
        if (base.length() > 120) {
            base = base.substring(0, 120).strip();
        }
        String marks = audio != null && subtitles != null
                ? " (" + audio + ", " + subtitles + " subs)"
                : audio != null ? " (" + audio + ")" : subtitles != null ? " (" + subtitles + " subs)" : "";
        return base + marks + ".mp4";
    }

    // ── Steps ─────────────────────────────────────────────────────────────

    private void transcribe(Video video, String language, long jobId, JobContext ctx) {
        whisper.requireReady();
        if (video.getVideoUrl() == null || video.getVideoUrl().isBlank()) {
            throw new JobFailure("The video has no media link to transcribe");
        }
        Path audio = media.extractAudio(video, mediaUrls.forServer(video.getVideoUrl()), ctx.slice(0, 30));
        List<Path> chunks = media.split(audio, ctx);
        List<TranscriptSegmentDto> segments = new ArrayList<>();
        String languageName = translator.name(language);
        if (WhisperClient.needsAutoDetect(language)) {
            ctx.info("OpenAI speech-to-text can't be told to expect " + languageName
                    + ", so it detects the language itself");
        }
        Set<String> heard = new LinkedHashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            ctx.progress(
                    30 + i * 65 / chunks.size(),
                    chunks.size() > 1
                            ? "Transcribing (part " + (i + 1) + " of " + chunks.size() + ")"
                            : "Transcribing");
            long offset = (long) i * MediaTools.CHUNK_SECONDS * 1000;
            WhisperClient.Result result = whisper.transcribe(chunks.get(i), language);
            if (result.detectedLanguage() != null) {
                heard.add(result.detectedLanguage());
            }
            for (WhisperClient.Segment s : result.segments()) {
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
        // The model names what it heard ("khmer"); say so if that isn't the video's language.
        String expected = languageName.toLowerCase(Locale.ROOT);
        List<String> other = heard.stream()
                .filter(h -> !expected.contains(h) && !h.contains(expected))
                .toList();
        if (!other.isEmpty()) {
            ctx.warn("Speech-to-text heard " + String.join(", ", other) + " rather than " + languageName
                    + " — check the transcript, and the video's spoken language");
        }
        ctx.progress(97, "Saving " + segments.size() + " segments");
        Long id = transcriptService.saveGenerated(video.getId(), language, segments, jobId, ACTOR);
        ctx.info("Transcribed " + segments.size() + " segments into transcript #" + id);
    }

    private void translate(Video video, String from, String to, boolean forSpeech, long jobId, JobContext ctx) {
        Transcript source = withText(video.getId(), from);
        if (source == null) {
            throw new JobFailure("There's no " + translator.name(from)
                    + " transcript with text to translate from — transcribe the video first");
        }
        List<TranscriptSegment> rows = segmentRepository.findByTranscriptIdOrderByPositionAsc(source.getId());
        List<String> translated = translator.translate(
                rows.stream().map(TranscriptSegment::getText).toList(),
                from,
                to,
                forSpeech,
                video.getId(),
                source.getId(),
                ctx.slice(0, 95));
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

    private void store(
            Video video,
            String language,
            String voice,
            Long transcriptId,
            Path file,
            String mime,
            long durationMs,
            long jobId,
            JobContext ctx) {
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

        VideoDub dub =
                dubRepository.findByVideoIdAndLanguage(video.getId(), language).orElse(null);
        String oldKey = dub != null ? dub.getStorageKey() : null;
        if (dub == null) {
            dub = VideoDub.builder()
                    .videoId(video.getId())
                    .language(language)
                    .createdBy(ACTOR)
                    .build();
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
        PutObjectRequest.Builder req =
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
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
        } catch (NoSuchKeyException e) {
            // Files uploaded only for one edit are removed after a while, so a retry can find them gone.
            if (isEditUpload(key)) {
                throw new JobFailure(
                        "An uploaded file for this job (" + key.substring(key.lastIndexOf('/') + 1)
                                + ") is no longer in storage — files uploaded for an edit " + "are kept for "
                                + CLIP_HOURS + " hours. Start the job again with a fresh upload.",
                        e);
            }
            throw new JobFailure("Couldn't read " + key + " from storage: it is missing", e);
        } catch (RuntimeException e) {
            throw new JobFailure("Couldn't read " + key + " from storage: " + e.getMessage(), e);
        }
        return out;
    }

    /** Whether this key is one of the temporary uploads made for an edit (audio, music or pictures). */
    static boolean isEditUpload(String key) {
        return key != null
                && (key.startsWith(AudioEditRules.UPLOAD_PREFIX) || key.startsWith(OverlayRules.UPLOAD_PREFIX));
    }

    /** One of our objects copied into `dir` (outside any job). */
    public Path download(String key, Path dir, String name) {
        requireBucket();
        String ext = key.contains(".") ? key.substring(key.lastIndexOf('.')) : "";
        Path out = dir.resolve(name + ext);
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), out);
        } catch (RuntimeException e) {
            throw new JobFailure("Couldn't read " + key + " from storage: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Deletes files under `prefix` last changed before `cutoff` — uploads made
     * only for an edit (replacement sound, music, overlay images), once no
     * job is likely to still need them. Returns how many went.
     */
    public int deleteStale(String prefix, java.time.Instant cutoff) {
        if (bucket == null || bucket.isBlank()) {
            return 0;
        }
        int deleted = 0;
        try {
            for (S3Object o : s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .build())
                    .contents()) {
                if (o.lastModified() != null && o.lastModified().isBefore(cutoff)) {
                    deleteObject(o.key());
                    deleted++;
                }
            }
        } catch (RuntimeException e) {
            // Storage unreachable: try again next time.
            return deleted;
        }
        return deleted;
    }

    void deleteObject(String key) {
        try {
            s3.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ignored) {
            // An orphaned file costs a little storage; not worth failing over.
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Transcript withText(Long videoId, String language) {
        return transcriptRepository
                .findByVideoIdAndLanguage(videoId, language)
                .filter(t -> t.getSegmentCount() > 0)
                .orElse(null);
    }

    private Video video(ProcessingJob job) {
        Video video = videoRepository
                .findById(job.getVideoId())
                .orElseThrow(() -> new JobFailure("The video no longer exists"));
        if (video.isDeleted()) {
            throw new JobFailure("The video is in the trash — restore it first");
        }
        return video;
    }

    private JsonNode params(ProcessingJob job) {
        try {
            return job.getParameters() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(job.getParameters());
        } catch (IOException e) {
            throw new JobFailure("The job's parameters aren't valid JSON");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }
}
