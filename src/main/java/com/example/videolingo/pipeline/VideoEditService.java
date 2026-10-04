package com.example.videolingo.pipeline;

import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoClip;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.VideoClipRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.service.VideoVersionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Trimming/cropping/scaling a range of a video, splitting it into segments,
// re-rendering its sound (AUDIO) or extracting it (EXTRACT) — all run as EDIT
// jobs, producing VideoClip rows an admin reviews before promoting (TRIM and
// AUDIO replace the video's file, SPLIT segments become new videos),
// downloading (EXTRACT) or discarding.
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoEditService {

    public record CropRect(int x, int y, int w, int h) {
    }

    public record ScaleSize(int w, int h) {
    }

    public record SegmentRange(long startMs, Long endMs) {
    }

    /** rotate = clockwise quarter turns in degrees (0/90/180/270); flips are applied after the turn. Crop is on the original picture, then turn/flip, then resize. */
    public record TrimRequest(long startMs, Long endMs, CropRect crop, ScaleSize scale, Integer rotate, Boolean flipH, Boolean flipV, TrimAudio audio,
                              Boolean extend, VideoEditRules.Look look) {
    }

    /** Sound added to a trim, on the trimmed video's own timeline: an uploaded file to use instead of the video's sound and/or background music. */
    public record TrimAudio(String replaceKey, MusicInput music) {
    }

    public record SplitRequest(List<SegmentRange> segments) {
    }

    /** Ranges to take out of the video (endMs null = to the end); what's left plays on as one video. */
    public record CutRequest(List<SegmentRange> cuts) {
    }

    // An audio edit as sent by the editor; anything left out keeps the sound as it is.
    public record AudioClipInput(long srcStartMs, Long srcEndMs, long atMs, Double gain) {
    }

    public record RangeInput(long startMs, long endMs) {
    }

    public record MusicInput(String key, Double volume, Boolean loop, Boolean duck, Long startMs) {
    }

    public record AudioRequest(String replaceKey, List<AudioClipInput> clips, List<RangeInput> mutes, Double volume, Long fadeInMs,
                               Long fadeOutMs, Boolean normalize, String denoise, Boolean enhanceVoice, Double speed, Double pitchSemitones,
                               Double balance, String channels, MusicInput music) {
    }

    // format: "MP3" (default) or "WAV".
    public record ExtractRequest(String format) {
    }

    public record Waveform(long durationMs, float[] peaks) {
    }

    public record ClipResponse(Long id, Long jobId, String operation, Integer segmentIndex, long startMs, Long endMs,
                               CropRect crop, ScaleSize scale, String url, long sizeBytes, Integer durationSeconds,
                               Integer width, Integer height, String summary, LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    public record EditOverview(List<ClipResponse> clips, List<ProcessingJobResponse> jobs) {
    }

    // kind: "REPLACED" (TRIM promoted onto the video) or "NEW_VIDEO" (a split segment became its own video).
    public record PromoteResult(String kind, Long newVideoId) {
    }

    private final VideoRepository videoRepository;
    private final VideoClipRepository clipRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobService jobService;
    private final PipelineSteps steps;
    private final VideoVersionService versionService;
    private final ObjectMapper objectMapper;
    private final MediaTools media;
    private final PipelineProperties pipelineProps;

    /** Recently drawn waveforms, by source + resolution (a file doesn't change under the same key). */
    private final Map<String, Waveform> waveforms = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Waveform> eldest) {
            return size() > 32;
        }
    });

    @Transactional(readOnly = true)
    public EditOverview overview(Long videoId) {
        findVideo(videoId);
        List<ClipResponse> clips = clipRepository.findByVideoIdAndExpiresAtAfterOrderByIdDesc(videoId, LocalDateTime.now())
                .stream().map(VideoEditService::toResponse).toList();
        List<ProcessingJobResponse> jobs = jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.EDIT)
                .stream().map(j -> jobService.getJob(j.getId())).toList();
        return new EditOverview(clips, jobs);
    }

    @Transactional
    public ProcessingJobResponse startTrim(Long videoId, TrimRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        boolean extend = Boolean.TRUE.equals(request.extend());
        Long videoMs = durationMs(video);
        require(extend ? VideoEditRules.validateExtendedTrim(request.startMs(), request.endMs(), videoMs)
                : VideoEditRules.validateTrim(request.startMs(), request.endMs(), videoMs));
        // How much of the result is past the video's own end; held as the last frame and silence.
        long padMs = extend && request.endMs() != null && videoMs != null ? Math.max(0, request.endMs() - videoMs) : 0;
        if (request.crop() != null) {
            require(VideoEditRules.validateCrop(request.crop().x(), request.crop().y(), request.crop().w(), request.crop().h(),
                    video.getWidth(), video.getHeight()));
        }
        if (request.scale() != null) {
            require(VideoEditRules.validateScale(request.scale().w(), request.scale().h()));
        }
        require(VideoEditRules.validateRotation(request.rotate()));
        require(VideoEditRules.validateLook(request.look()));
        AudioEditRules.Spec audio = null;
        if (request.audio() != null) {
            audio = toSpec(new AudioRequest(request.audio().replaceKey(), null, null, null, null, null, null, null, null, null, null, null, null,
                    request.audio().music()));
            Long total = durationMs(video);
            long end = request.endMs() != null ? request.endMs() : total != null ? total : -1;
            require(AudioEditRules.validate(audio, end > request.startMs() ? end - request.startMs() : null));
        }
        requireNoActiveJob(videoId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "TRIM");
        params.put("startMs", request.startMs());
        if (request.endMs() != null) {
            params.put("endMs", request.endMs());
        }
        if (request.crop() != null) {
            params.put("crop", Map.of("x", request.crop().x(), "y", request.crop().y(), "w", request.crop().w(), "h", request.crop().h()));
        }
        if (request.scale() != null) {
            params.put("scale", Map.of("w", request.scale().w(), "h", request.scale().h()));
        }
        if (request.rotate() != null && request.rotate() != 0) {
            params.put("rotate", request.rotate());
        }
        if (Boolean.TRUE.equals(request.flipH())) {
            params.put("flipH", true);
        }
        if (Boolean.TRUE.equals(request.flipV())) {
            params.put("flipV", true);
        }
        if (audio != null) {
            params.put("audio", audio);
        }
        if (padMs > 0) {
            params.put("padMs", padMs);
        }
        if (request.look() != null && !request.look().isPlain()) {
            params.put("look", request.look());
        }
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params), "Trim/crop requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startSplit(Long videoId, SplitRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        List<SegmentRange> segments = request.segments() == null ? List.of() : request.segments();
        require(VideoEditRules.validateSegments(segments.stream().map(s -> new VideoEditRules.Segment(s.startMs(), s.endMs())).toList(),
                durationMs(video)));
        requireNoActiveJob(videoId);

        List<Map<String, Object>> segmentParams = segments.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", s.startMs());
            if (s.endMs() != null) {
                m.put("endMs", s.endMs());
            }
            return m;
        }).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "SPLIT");
        params.put("segments", segmentParams);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params),
                "Split into " + segments.size() + " segment(s) requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startCut(Long videoId, CutRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        List<SegmentRange> cuts = request == null || request.cuts() == null ? List.of() : request.cuts();
        List<VideoEditRules.Segment> segments = cuts.stream().map(c -> new VideoEditRules.Segment(c.startMs(), c.endMs())).toList();
        require(VideoEditRules.validateCuts(segments, durationMs(video)));
        requireNoActiveJob(videoId);

        List<Map<String, Object>> cutParams = VideoEditRules.mergeCuts(segments).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", c.startMs());
            if (c.endMs() != null) {
                m.put("endMs", c.endMs());
            }
            return m;
        }).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "CUT");
        params.put("cuts", cutParams);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params),
                "Cut out " + cutParams.size() + " range(s) requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startAudio(Long videoId, AudioRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        AudioEditRules.Spec spec = toSpec(request);
        require(AudioEditRules.validate(spec, durationMs(video)));
        requireNoActiveJob(videoId);

        String summary = AudioEditRules.describe(spec);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "AUDIO");
        params.put("audio", spec);
        params.put("summary", summary);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params), "Audio edit (" + summary + ") requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startExtract(Long videoId, ExtractRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        String format = request == null || request.format() == null ? "MP3" : request.format().toUpperCase(java.util.Locale.ROOT);
        if (!format.equals("MP3") && !format.equals("WAV")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Audio can be extracted as MP3 or WAV");
        }
        requireNoActiveJob(videoId);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(Map.of("operation", "EXTRACT", "format", format)),
                "Extract audio (" + format + ") requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startOverlay(Long videoId, OverlayRules.Spec request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        OverlayRules.Spec spec = request == null ? new OverlayRules.Spec(List.of()) : request;
        require(OverlayRules.validate(spec, durationMs(video)));
        if (spec.layers().stream().anyMatch(l -> l.textual() && !TextRenderer.fonts().contains(l.font()))) {
            throw new AppException(HttpStatus.BAD_REQUEST, "One of the fonts isn't installed on the server — pick another");
        }
        requireNoActiveJob(videoId);
        String summary = OverlayRules.describe(spec);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "OVERLAY");
        params.put("overlay", spec);
        params.put("summary", summary);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params), "Text & overlay (" + summary + ") requested by " + username);
        return jobService.getJob(job.getId());
    }

    public List<String> fonts() {
        return TextRenderer.fonts();
    }

    /**
     * The loudness outline of the video's sound — or of an uploaded audio
     * file (key) — in `points` slices, for drawing under the audio clips.
     */
    public Waveform waveform(Long videoId, String key, int points) {
        Video video = findVideo(videoId);
        int n = Math.max(100, Math.min(points, 8000));
        if (key != null && !key.isBlank() && !AudioEditRules.isUploadKey(key)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "That isn't an uploaded audio file");
        }
        String source = key != null && !key.isBlank() ? key : video.getStorageKey() != null ? video.getStorageKey() : video.getVideoUrl();
        if ((key == null || key.isBlank()) && PipelineSteps.isLink(video.getSource())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Link videos have no sound file to draw");
        }
        String cacheKey = source + "@" + n;
        Waveform cached = waveforms.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory(Path.of(pipelineProps.workDirectory()), "waveform-");
            String input = key != null && !key.isBlank() ? steps.download(key, dir, "audio").toString()
                    : video.getStorageKey() != null ? steps.download(video.getStorageKey(), dir, "video").toString()
                    : video.getVideoUrl();
            MediaTools.Peaks peaks = media.peaks(input, n, dir);
            Waveform result = new Waveform(peaks.durationMs(), peaks.values());
            waveforms.put(cacheKey, result);
            return result;
        } catch (JobFailure e) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (java.io.IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Couldn't create a temporary folder");
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Cached motion heatmaps, by source — a re-decode isn't needed just because the aspect changed. */
    private final Map<String, float[]> motionCache = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > 16;
        }
    });

    /**
     * A crop box of `aspect` (w/h) centred on wherever the video moves the
     * most, for the "Auto-center" button. Falls back to the frame's centre
     * for a static video, or one with no visible width/height.
     */
    public CropRect suggestCrop(Long videoId, double aspect) {
        if (aspect <= 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Give a valid aspect ratio");
        }
        Video video = findVideo(videoId);
        requireEditable(video);
        Path dir = null;
        try {
            String source = video.getStorageKey() != null ? video.getStorageKey() : video.getVideoUrl();
            float[] heat = motionCache.get(source);
            Integer width = video.getWidth();
            Integer height = video.getHeight();
            if (heat == null || width == null || height == null) {
                dir = Files.createTempDirectory(Path.of(pipelineProps.workDirectory()), "autocrop-");
                String input = video.getStorageKey() != null ? steps.download(video.getStorageKey(), dir, "video").toString() : video.getVideoUrl();
                if (width == null || height == null) {
                    MediaTools.Probe probe = media.probe(input, dir);
                    width = probe.width();
                    height = probe.height();
                }
                if (heat == null) {
                    heat = media.motionHeatmap(input, dir);
                    motionCache.put(source, heat);
                }
            }
            if (width == null || height == null || width <= 0 || height <= 0) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "Couldn't read the video's picture size");
            }
            AutoCropRules.Centroid centroid = AutoCropRules.centroid(heat, MediaTools.MOTION_COLS, MediaTools.MOTION_ROWS);
            AutoCropRules.CropRect r = AutoCropRules.suggestCrop(aspect, width, height, centroid.x(), centroid.y());
            return new CropRect(r.x(), r.y(), r.w(), r.h());
        } catch (JobFailure e) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (java.io.IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Couldn't create a temporary folder");
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // Temp space.
                }
            });
        } catch (java.io.IOException ignored) {
            // As above.
        }
    }

    private static AudioEditRules.Spec toSpec(AudioRequest r) {
        if (r == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nothing to change — pick at least one audio change");
        }
        List<AudioEditRules.Clip> clips = r.clips() == null ? List.of() : r.clips().stream()
                .map(c -> new AudioEditRules.Clip(c.srcStartMs(), c.srcEndMs() == null ? Long.MAX_VALUE : c.srcEndMs(), c.atMs(),
                        c.gain() == null ? 1 : c.gain()))
                .toList();
        List<AudioEditRules.Range> mutes = r.mutes() == null ? List.of() : r.mutes().stream()
                .map(m -> new AudioEditRules.Range(m.startMs(), m.endMs())).toList();
        AudioEditRules.Music music = r.music() == null || r.music().key() == null ? null : new AudioEditRules.Music(r.music().key(),
                r.music().volume() == null ? 0.3 : r.music().volume(), r.music().loop() == null || r.music().loop(),
                Boolean.TRUE.equals(r.music().duck()), r.music().startMs() == null ? 0 : r.music().startMs());
        String replaceKey = r.replaceKey() == null || r.replaceKey().isBlank() ? null : r.replaceKey();
        return new AudioEditRules.Spec(replaceKey, clips, mutes, r.volume() == null ? 1 : r.volume(),
                r.fadeInMs() == null ? 0 : r.fadeInMs(), r.fadeOutMs() == null ? 0 : r.fadeOutMs(), Boolean.TRUE.equals(r.normalize()),
                r.denoise() == null ? "OFF" : r.denoise(), Boolean.TRUE.equals(r.enhanceVoice()), r.speed() == null ? 1 : r.speed(),
                r.pitchSemitones() == null ? 0 : r.pitchSemitones(), r.balance() == null ? 0 : r.balance(),
                r.channels() == null ? "KEEP" : r.channels(), music);
    }

    public PromoteResult promote(Long videoId, Long clipId, String username) {
        return promote(videoId, clipId, username, false, null);
    }

    /**
     * Applies a result. A trim, audio edit or text & overlay result replaces the video's file, unless
     * `asNew` asks for a separate video instead (the original is then left as it is). Split segments
     * always become new videos. `title` names the new video; left out, one is made from the original's.
     */
    @Transactional
    public PromoteResult promote(Long videoId, Long clipId, String username, boolean asNew, String title) {
        VideoClip clip = findClip(videoId, clipId);
        if (clip.getOperation() == VideoClip.Operation.EXTRACT) {
            throw new AppException(HttpStatus.BAD_REQUEST, "An extracted audio file can't replace the video — download it instead");
        }
        if (clip.getOperation().replacesVideo() && !asNew) {
            Video video = findVideo(videoId);
            // The superseded file becomes a version instead of being deleted.
            versionService.snapshot(video, clip.getOperation() == VideoClip.Operation.AUDIO ? "Replaced by an audio edit" : "Replaced by a trim/crop edit",
                    username);
            video.setStorageKey(clip.getStorageKey());
            video.setVideoUrl(clip.getUrl());
            video.setFileSize(clip.getSizeBytes());
            video.setMimeType("video/mp4");
            if (clip.getDurationSeconds() != null) {
                video.setDurationSeconds(clip.getDurationSeconds());
            }
            if (clip.getWidth() != null) {
                video.setWidth(clip.getWidth());
            }
            if (clip.getHeight() != null) {
                video.setHeight(clip.getHeight());
            }
            videoRepository.save(video);
            clipRepository.delete(clip);
            log.info("Video #{} replaced with an edited clip by {}", videoId, username);
            return new PromoteResult("REPLACED", null);
        }

        Video source = findVideo(videoId);
        String newTitle = promotedTitle(source.getTitle(), clip.getOperation(), clip.getSegmentIndex(), title);
        Video created = videoRepository.save(Video.builder()
                .title(newTitle)
                .ownerId(source.getOwnerId())
                .language(source.getLanguage())
                .videoUrl(clip.getUrl())
                .storageKey(clip.getStorageKey())
                .source(VideoSource.UPLOAD)
                .durationSeconds(clip.getDurationSeconds())
                .width(clip.getWidth())
                .height(clip.getHeight())
                .fileSize(clip.getSizeBytes())
                .mimeType("video/mp4")
                .enabled(false)
                .build());
        clipRepository.delete(clip);
        log.info("Video #{} created from a {} result of video #{} by {}", created.getId(), clip.getOperation(), videoId, username);
        return new PromoteResult("NEW_VIDEO", created.getId());
    }

    /** The longest a video title may be (the column's length). */
    static final int MAX_TITLE = 200;

    /**
     * The title of a video made from a result: `custom` if one was given, otherwise the original's with
     * what was done to it ("… — Part 2", "… (trimmed)"). Always within the title limit.
     */
    static String promotedTitle(String sourceTitle, VideoClip.Operation operation, Integer segmentIndex, String custom) {
        if (custom != null && !custom.isBlank()) {
            String t = custom.strip();
            return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
        }
        String suffix = switch (operation) {
            case SPLIT -> " — Part " + ((segmentIndex == null ? 0 : segmentIndex) + 1);
            case TRIM -> " (trimmed)";
            case AUDIO -> " (edited audio)";
            case OVERLAY -> " (with text & overlays)";
            case EXTRACT -> " (audio)";
        };
        String base = sourceTitle == null ? "Video" : sourceTitle.strip();
        // The end of the title is the part that says what this is, so the original's is what gets shortened.
        int room = MAX_TITLE - suffix.length();
        return (base.length() > room ? base.substring(0, Math.max(0, room - 1)) + "…" : base) + suffix;
    }

    @Transactional
    public void remove(Long videoId, Long clipId) {
        VideoClip clip = findClip(videoId, clipId);
        clipRepository.delete(clip);
        steps.deleteObject(clip.getStorageKey());
    }

    // Unpromoted clips are temporary: remove expired ones and their files.
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void deleteExpired() {
        List<VideoClip> expired = clipRepository.findByExpiresAtBefore(LocalDateTime.now());
        for (VideoClip c : expired) {
            steps.deleteObject(c.getStorageKey());
        }
        clipRepository.deleteAll(expired);
        if (!expired.isEmpty()) {
            log.info("Deleted {} expired video clip(s)", expired.size());
        }
        // Files uploaded only for an edit: kept as long as its results are.
        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(PipelineSteps.CLIP_HOURS));
        int stale = steps.deleteStale(AudioEditRules.UPLOAD_PREFIX, cutoff) + steps.deleteStale(OverlayRules.UPLOAD_PREFIX, cutoff);
        if (stale > 0) {
            log.info("Deleted {} edit upload(s) older than {} hours", stale, PipelineSteps.CLIP_HOURS);
        }
    }

    private void requireEditable(Video video) {
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video first");
        }
        if (PipelineSteps.isLink(video.getSource())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Import this video into storage first — link videos can't be edited directly");
        }
    }

    /** At most this many of a video's edits may be queued/running at once — they still run one at a time (the worker is single-threaded), this just caps how far ahead you can queue. */
    static final int MAX_QUEUED_EDITS_PER_VIDEO = 3;

    private void requireNoActiveJob(Long videoId) {
        long active = jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.EDIT).stream()
                .filter(j -> j.getStatus().isActive()).count();
        if (active >= MAX_QUEUED_EDITS_PER_VIDEO) {
            throw new AppException(HttpStatus.CONFLICT,
                    "This video already has " + active + " edits queued or running — wait for one to finish before starting another");
        }
    }

    private static void require(String error) {
        if (error != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, error);
        }
    }

    private static Long durationMs(Video video) {
        return video.getDurationSeconds() == null ? null : video.getDurationSeconds() * 1000L;
    }

    private Video findVideo(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private VideoClip findClip(Long videoId, Long clipId) {
        return clipRepository.findById(clipId)
                .filter(c -> c.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Clip not found"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClipResponse toResponse(VideoClip c) {
        CropRect crop = c.hasCrop() ? new CropRect(c.getCropX(), c.getCropY(), c.getCropW(), c.getCropH()) : null;
        ScaleSize scale = c.getScaleW() != null && c.getScaleH() != null ? new ScaleSize(c.getScaleW(), c.getScaleH()) : null;
        return new ClipResponse(c.getId(), c.getJobId(), c.getOperation().name(), c.getSegmentIndex(), c.getStartMs(), c.getEndMs(),
                crop, scale, c.getUrl(), c.getSizeBytes(), c.getDurationSeconds(), c.getWidth(), c.getHeight(), c.getSummary(), c.getCreatedAt(),
                c.getExpiresAt());
    }
}
