package com.example.videolingo.ingest;

import com.example.videolingo.dto.VideoIngestDtos.AudioToVideoRequest;
import com.example.videolingo.dto.VideoIngestDtos.AudioToVideoResponse;
import com.example.videolingo.dto.VideoIngestDtos.CreateVideoRequest;
import com.example.videolingo.dto.VideoIngestDtos.Duplicate;
import com.example.videolingo.dto.VideoIngestDtos.InspectResponse;
import com.example.videolingo.dto.VideoIngestDtos.LanguageGuess;
import com.example.videolingo.dto.VideoIngestDtos.MergeVideosRequest;
import com.example.videolingo.dto.VideoIngestDtos.MergeVideosResponse;
import com.example.videolingo.dto.VideoIngestDtos.ReplaceRequest;
import com.example.videolingo.dto.VideoIngestDtos.UploadRequest;
import com.example.videolingo.dto.VideoIngestDtos.UploadTicket;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.pipeline.AudioEditRules;
import com.example.videolingo.pipeline.AudioToVideoRules;
import com.example.videolingo.pipeline.MergeRules;
import com.example.videolingo.service.ProcessingJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.videolingo.pipeline.OverlayRules;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.entity.Language;
import com.example.videolingo.entity.User;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.CategoryService;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.VideoService;
import com.example.videolingo.service.VideoVersionService;
import com.example.videolingo.settings.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// Add Video: inspect a pasted link, sign direct-to-storage uploads, and create
// the video record from either. Metadata the admin reviewed in the form is
// taken as given; what identifies the media (platform, id, file) is always
// re-derived on the server.
@Service
@RequiredArgsConstructor
public class VideoIngestService {

    static final Map<String, String> VIDEO_TYPES = Map.of(
            "mp4", "video/mp4", "m4v", "video/x-m4v", "webm", "video/webm", "mov", "video/quicktime",
            "ogv", "video/ogg", "mkv", "video/x-matroska");
    static final Map<String, String> IMAGE_TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp");
    // Replacement sound and background music for audio edits (AudioEditRules.UPLOAD_PREFIX).
    static final Map<String, String> AUDIO_TYPES = Map.of("mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "wav", "audio/wav",
            "ogg", "audio/ogg", "oga", "audio/ogg", "opus", "audio/opus", "flac", "audio/flac", "weba", "audio/webm");
    private static final long MAX_THUMBNAIL_BYTES = 5L * 1024 * 1024;
    private static final Duration TICKET_TTL = Duration.ofHours(2);
    private static final Pattern UPLOADED_KEY = Pattern.compile("^videos/[0-9a-f-]{36}\\.[a-z0-9]{2,4}$");
    private static final Pattern THUMB_KEY = Pattern.compile("^thumbnails/[0-9a-f-]{36}\\.[a-z0-9]{2,4}$");

    private final VideoInspector inspector;
    private final VideoRepository videoRepository;
    private final LanguageRepository languageRepository;
    private final LanguageService languageService;
    private final CategoryService categoryService;
    private final VideoService videoService;
    private final UserRepository userRepository;
    private final SettingsService settings;
    private final VideoVersionService versionService;
    private final ProcessingJobService jobService;
    private final ObjectMapper objectMapper;
    private final S3Client s3;
    private final S3Presigner presigner;

    @Value("${s3.bucket:}")
    private String bucket;

    @Value("${s3.public-endpoint:}")
    private String publicEndpoint;

    // ── inspect ───────────────────────────────────────────────────────────

    public InspectResponse inspect(String url) {
        VideoLinks.Parsed parsed = parse(url);
        VideoInspector.Facts f = inspector.inspect(parsed);
        VideoLinks.Parsed link = f.link();
        LanguageGuess language = f.platformLanguage() != null
                ? guess(f.platformLanguage(), 0.95, "platform")
                : textGuess(f.title(), f.description());
        return new InspectResponse(f.url(), link.source(), link.kind().name(), link.externalId(), link.embedUrl(), f.title(), f.description(),
                f.durationSeconds(), f.thumbnailUrl(), f.width(), f.height(), f.author(), f.mimeType(), f.fileSize(), language, f.warnings(),
                duplicates(link.source(), link.externalId(), f.url()));
    }

    public LanguageGuess detectLanguage(String title, String description) {
        return textGuess(title, description);
    }

    private LanguageGuess textGuess(String title, String description) {
        LanguageDetector.Guess g = LanguageDetector.detect(title, description);
        return g == null ? null : guess(g.code(), g.confidence(), "text");
    }

    private LanguageGuess guess(String rawCode, double confidence, String source) {
        String code = rawCode.split("-")[0].toLowerCase(Locale.ROOT);
        // Prefer an exact regional match from the catalog (e.g. "pt-BR"), else the base language.
        Language lang = languageRepository.findByCodeIgnoreCase(rawCode).or(() -> languageRepository.findByCodeIgnoreCase(code)).orElse(null);
        if (lang == null) {
            return new LanguageGuess(code, null, confidence, source, false, false);
        }
        return new LanguageGuess(lang.getCode(), lang.getName(), confidence, source, true, lang.isEnabled());
    }

    private List<Duplicate> duplicates(VideoSource source, String externalId, String url) {
        // "" never matches a stored id (those are null or non-empty).
        return videoRepository.findDuplicates(source, externalId == null ? "" : externalId, url).stream()
                .map(v -> new Duplicate(v.getId(), v.getTitle(), v.getDeletedAt() != null)).toList();
    }

    private static VideoLinks.Parsed parse(String url) {
        try {
            return VideoLinks.parse(url);
        } catch (IllegalArgumentException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ── uploads ───────────────────────────────────────────────────────────

    /** Signs a PUT of exactly {@code size} bytes of the declared type to a fresh key. */
    public UploadTicket presignUpload(UploadRequest r) {
        requireStorage();
        boolean video = r.getKind().equals("VIDEO");
        boolean audio = r.getKind().equals("AUDIO");
        boolean overlay = r.getKind().equals("OVERLAY");
        String ext = extension(r.getFileName());
        Map<String, String> types = video ? VIDEO_TYPES : audio ? AUDIO_TYPES : IMAGE_TYPES;
        String declared = r.getContentType() == null ? "" : r.getContentType().split(";")[0].strip().toLowerCase(Locale.ROOT);
        // Browsers label WAV as audio/x-wav or audio/wave, and MP3 sometimes as audio/mp3.
        declared = switch (declared) {
            case "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav";
            case "audio/mp3", "audio/x-mpeg" -> "audio/mpeg";
            case "audio/x-m4a" -> "audio/mp4";
            case "audio/x-flac" -> "audio/flac";
            default -> declared;
        };
        String contentType = types.containsValue(declared) ? declared : types.get(ext);
        if (contentType == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, video
                    ? "That file type isn't supported — upload MP4, WebM, MOV, M4V, OGV or MKV"
                    : audio ? "That audio type isn't supported — upload MP3, M4A, AAC, WAV, OGG, Opus or FLAC"
                    : overlay ? "Overlay images must be PNG, JPEG or WebP" : "Thumbnails must be JPEG, PNG or WebP images");
        }
        long max = video || audio ? settings.video().maxVideoUploadMb() * 1024L * 1024L : MAX_THUMBNAIL_BYTES;
        if (r.getSize() > max) {
            throw new AppException(HttpStatus.BAD_REQUEST, (video ? "Videos" : audio ? "Audio files" : "Thumbnails") + " can be at most "
                    + (max / (1024 * 1024)) + " MB" + (video || audio ? " (Settings › Video)" : ""));
        }
        String fileExt = types.entrySet().stream().filter(e -> e.getValue().equals(contentType) && e.getKey().equals(ext)).map(Map.Entry::getKey)
                .findFirst().orElseGet(() -> types.entrySet().stream().filter(e -> e.getValue().equals(contentType)).map(Map.Entry::getKey).sorted().findFirst().orElseThrow());
        String key = (video ? "videos/" : audio ? AudioEditRules.UPLOAD_PREFIX : overlay ? OverlayRules.UPLOAD_PREFIX : "thumbnails/")
                + UUID.randomUUID() + "." + fileExt;
        PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(TICKET_TTL)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(r.getSize()).build())
                .build());
        return new UploadTicket(key, signed.url().toString(), "PUT", Map.of("Content-Type", contentType), signed.expiration(), publicUrl(key), contentType);
    }

    // ── create ────────────────────────────────────────────────────────────

    @Transactional
    public VideoResponse create(CreateVideoRequest r, String actor) {
        Video.VideoBuilder video = Video.builder()
                .title(r.getTitle().strip())
                .description(blankToNull(r.getDescription()))
                .durationSeconds(r.getDurationSeconds() == null || r.getDurationSeconds() == 0 ? null : r.getDurationSeconds())
                .width(r.getWidth())
                .height(r.getHeight())
                .enabled(r.isEnabled())
                .ownerId(userRepository.findByUsername(actor).map(User::getId).orElse(null));

        if (r.getMode().equals("UPLOAD")) {
            fromUpload(r, video);
        } else {
            fromLink(r, video);
        }

        String language = blankToNull(r.getLanguage());
        video.language(language == null ? null : languageService.resolve(language, true));
        video.thumbnailUrl(thumbnail(r.getThumbnailUrl()));

        List<Long> categories = r.getCategoryIds() == null ? List.of() : r.getCategoryIds();
        Set<Long> resolved = categoryService.resolveForVideo(categories, Set.of());
        int maxCategories = settings.video().maxCategoriesPerVideo();
        if (resolved.size() > maxCategories) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A video can be in at most " + maxCategories + " categor" + (maxCategories == 1 ? "y" : "ies"));
        }
        if (resolved.isEmpty() && settings.video().requireCategory()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose at least one category — Settings › Video requires one");
        }
        Video built = video.build();
        built.getCategoryIds().addAll(resolved);
        Video saved = videoRepository.save(built);
        return videoService.getVideo(saved.getId());
    }

    // ── from audio ────────────────────────────────────────────────────────

    /**
     * Makes a video from an uploaded sound. The video row is created right away
     * — disabled, so learners can't see it — and a job renders its file (a still
     * picture with the sound) and fills the row in, the same way an upload is
     * reviewed before it is enabled.
     */
    @Transactional
    public AudioToVideoResponse createFromAudio(AudioToVideoRequest r, String actor) {
        requireStorage();
        String cardText = blankToNull(r.getCardText());
        List<AudioToVideoRules.Slide> slides = r.getSlides() == null ? List.of()
                : r.getSlides().stream().map(x -> new AudioToVideoRules.Slide(x.key(), x.startMs())).toList();
        AudioToVideoRules.Spec spec = new AudioToVideoRules.Spec(r.getAudioKey(), blankToNull(r.getCoverKey()), blankToNull(r.getBackground()),
                blankToNull(r.getResolution()), blankToNull(r.getWaveform()), blankToNull(r.getWaveColor()), r.isTitleCard(),
                r.isTitleCard() && slides.isEmpty() && blankToNull(r.getCoverKey()) == null ? (cardText != null ? cardText : r.getTitle().strip()) : null,
                r.isNormalize(), r.isDenoise(), slides);
        String problem = AudioToVideoRules.validate(spec);
        if (problem != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, problem);
        }
        if (head(spec.audioKey()) == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The audio file wasn't found — the upload may not have finished");
        }
        for (AudioToVideoRules.Slide slide : spec.slides()) {
            if (head(slide.key()) == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "A picture wasn't found — the upload may not have finished");
            }
        }

        String language = blankToNull(r.getLanguage());
        Set<Long> categories = categoryService.resolveForVideo(r.getCategoryIds() == null ? List.of() : r.getCategoryIds(), Set.of());
        int maxCategories = settings.video().maxCategoriesPerVideo();
        if (categories.size() > maxCategories) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A video can be in at most " + maxCategories + " categor" + (maxCategories == 1 ? "y" : "ies"));
        }
        if (categories.isEmpty() && settings.video().requireCategory()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose at least one category — Settings › Video requires one");
        }

        // Until the job finishes the row points at the sound, which is what there is to play.
        Video built = Video.builder()
                .title(r.getTitle().strip())
                .description(blankToNull(r.getDescription()))
                .ownerId(userRepository.findByUsername(actor).map(User::getId).orElse(null))
                .language(language == null ? null : languageService.resolve(language, true))
                .source(VideoSource.UPLOAD)
                .videoUrl(publicUrl(spec.audioKey()))
                .enabled(false)
                .build();
        built.getCategoryIds().addAll(categories);
        Video saved = videoRepository.save(built);

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("operation", "AUDIO_TO_VIDEO");
        params.put("audioKey", spec.audioKey());
        if (spec.coverKey() != null) {
            params.put("coverKey", spec.coverKey());
        }
        if (spec.slides().size() > 1) {
            params.put("slides", spec.slides().stream().map(x -> Map.of("key", x.key(), "startMs", x.startMs())).toList());
        }
        if (spec.background() != null) {
            params.put("background", spec.background());
        }
        params.put("resolution", spec.resolution() == null ? AudioToVideoRules.DEFAULT_RESOLUTION : spec.resolution());
        if (spec.hasWaveform()) {
            params.put("waveform", spec.waveform());
            if (spec.waveColor() != null) {
                params.put("waveColor", spec.waveColor());
            }
        }
        if (spec.drawsTitle()) {
            params.put("titleCard", true);
            params.put("titleText", spec.titleText());
        }
        if (spec.normalize()) {
            params.put("normalize", true);
        }
        if (spec.denoise()) {
            params.put("denoise", true);
        }
        if (r.isTranscribe()) {
            if (language == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Choose the spoken language to transcribe the video");
            }
            params.put("transcribe", true);
        }
        params.put("summary", AudioToVideoRules.describe(spec));
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var job = jobService.enqueue(saved.getId(), ProcessingJobType.EDIT, json, AudioToVideoRules.describe(spec) + " requested by " + actor);
        return new AudioToVideoResponse(videoService.getVideo(saved.getId()), jobService.getJob(job.getId()));
    }

    // ── merge ─────────────────────────────────────────────────────────────

    /**
     * Joins stored videos into one. The new video row is created right away —
     * hidden, so learners can't see it — and a job renders its file from the
     * originals, which are left as they are.
     */
    @Transactional
    public MergeVideosResponse mergeVideos(MergeVideosRequest r, String actor) {
        requireStorage();
        String resolution = blankToNull(r.getResolution());
        String transition = blankToNull(r.getTransition());
        String problem = MergeRules.validate(r.getVideoIds(), resolution, transition);
        if (problem != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, problem);
        }

        // Each one must exist, be a stored file (a link has no file to join), and not be in the trash.
        List<Video> sources = new java.util.ArrayList<>();
        for (Long id : r.getVideoIds()) {
            Video v = videoRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + id));
            if (v.isDeleted()) {
                throw new AppException(HttpStatus.CONFLICT, "\"" + v.getTitle() + "\" is in the trash — restore it or leave it out");
            }
            if (v.getStorageKey() == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "\"" + v.getTitle() + "\" is a link, not a stored file — import it first");
            }
            sources.add(v);
        }
        // Lengths known up front save queueing a job that would only fail on the limit.
        long knownMs = sources.stream().mapToLong(v -> v.getDurationSeconds() == null ? 0 : v.getDurationSeconds() * 1000L).sum();
        if (knownMs > MergeRules.MAX_TOTAL_MS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The joined video would be longer than " + (MergeRules.MAX_TOTAL_MS / 3_600_000) + " hours");
        }

        String language = blankToNull(r.getLanguage());
        if (language == null && sources.stream().map(Video::getLanguage).distinct().count() == 1) {
            language = sources.get(0).getLanguage(); // all the same language: the joined video is too
        }
        Set<Long> categories = categoryService.resolveForVideo(r.getCategoryIds() == null ? List.of() : r.getCategoryIds(), Set.of());
        int maxCategories = settings.video().maxCategoriesPerVideo();
        if (categories.size() > maxCategories) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A video can be in at most " + maxCategories + " categor" + (maxCategories == 1 ? "y" : "ies"));
        }
        if (categories.isEmpty() && settings.video().requireCategory()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose at least one category — Settings › Video requires one");
        }

        // Until the job finishes the row points at the first video, so it has something to play.
        Video built = Video.builder()
                .title(r.getTitle().strip())
                .description(blankToNull(r.getDescription()))
                .ownerId(userRepository.findByUsername(actor).map(User::getId).orElse(null))
                .language(language == null ? null : languageService.resolve(language, true))
                .source(VideoSource.UPLOAD)
                .videoUrl(sources.get(0).getVideoUrl())
                .enabled(false)
                .build();
        built.getCategoryIds().addAll(categories);
        Video saved = videoRepository.save(built);

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("operation", "MERGE");
        params.put("videoIds", r.getVideoIds());
        params.put("resolution", resolution == null ? AudioToVideoRules.DEFAULT_RESOLUTION : resolution);
        if (transition != null) {
            params.put("transition", transition);
        }
        String summary = MergeRules.describe(r.getVideoIds().size(), resolution, transition);
        params.put("summary", summary);
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var job = jobService.enqueue(saved.getId(), ProcessingJobType.EDIT, json, summary + " requested by " + actor);
        return new MergeVideosResponse(videoService.getVideo(saved.getId()), jobService.getJob(job.getId()));
    }

    // ── replace ───────────────────────────────────────────────────────────

    /** Swaps a video's file for a freshly uploaded one; the old file is kept as a version (VideoVersionService). */
    @Transactional
    public VideoResponse replace(Long videoId, ReplaceRequest r, String actingUsername) {
        requireStorage();
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before replacing its file");
        }
        String key = r.getStorageKey();
        if (key == null || !UPLOADED_KEY.matcher(key).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Upload the new video file first");
        }
        HeadObjectResponse head = head(key);
        if (head == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The uploaded file wasn't found — the upload may not have finished");
        }
        versionService.snapshot(video, "Replaced by upload", actingUsername);
        video.setSource(VideoSource.UPLOAD);
        video.setStorageKey(key);
        video.setVideoUrl(publicUrl(key));
        video.setFileSize(head.contentLength());
        video.setMimeType(head.contentType());
        if (r.getDurationSeconds() != null) {
            video.setDurationSeconds(r.getDurationSeconds());
        }
        if (r.getWidth() != null) {
            video.setWidth(r.getWidth());
        }
        if (r.getHeight() != null) {
            video.setHeight(r.getHeight());
        }
        videoRepository.save(video);
        return videoService.getVideo(videoId);
    }

    private void fromLink(CreateVideoRequest r, Video.VideoBuilder video) {
        if (r.getUrl() == null || r.getUrl().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Paste the video's link");
        }
        VideoLinks.Parsed link = parse(r.getUrl());
        String url = link.canonicalUrl();
        // Short links and pages only reveal the real video after fetching them, and
        // a file link must be a reachable, public video — the same checks as Inspect.
        if (link.externalId() == null) {
            try {
                VideoInspector.Facts f = inspector.inspect(link);
                link = f.link();
                url = f.url();
            } catch (VideoInspector.InspectException e) {
                throw new AppException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        if (!r.isAllowDuplicate()) {
            List<Duplicate> dups = duplicates(link.source(), link.externalId(), url);
            if (!dups.isEmpty()) {
                Duplicate d = dups.get(0);
                throw new AppException(HttpStatus.CONFLICT, "This video was already added as “" + d.title() + "” (#" + d.id() + ")"
                        + (d.trashed() ? " — it's in the trash" : ""));
            }
        }
        video.source(link.source())
                .externalId(link.externalId())
                .videoUrl(url)
                .sourceAuthor(blankToNull(r.getSourceAuthor()))
                .mimeType(link.source() == VideoSource.URL ? blankToNull(r.getMimeType()) : null);
    }

    private void fromUpload(CreateVideoRequest r, Video.VideoBuilder video) {
        requireStorage();
        String key = r.getStorageKey();
        if (key == null || !UPLOADED_KEY.matcher(key).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Upload the video file first");
        }
        HeadObjectResponse head = head(key);
        if (head == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The uploaded file wasn't found — the upload may not have finished");
        }
        if (videoRepository.findDuplicates(VideoSource.UPLOAD, "", publicUrl(key)).stream().findAny().isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "This upload is already attached to a video");
        }
        video.source(VideoSource.UPLOAD)
                .storageKey(key)
                .videoUrl(publicUrl(key))
                .fileSize(head.contentLength())
                .mimeType(head.contentType());
    }

    // A thumbnail we stored ourselves must exist; any other http(s) image address is taken as given.
    private String thumbnail(String url) {
        String t = blankToNull(url);
        if (t == null) {
            return null;
        }
        String ourPrefix = publicUrl("");
        if (!bucket.isBlank() && t.startsWith(ourPrefix)) {
            String key = t.substring(ourPrefix.length());
            if (!THUMB_KEY.matcher(key).matches() || head(key) == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "The thumbnail upload wasn't found — try capturing it again");
            }
        }
        return t;
    }

    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw new AppException(HttpStatus.BAD_GATEWAY, "Storage couldn't be reached: " + e.awsErrorDetails().errorMessage());
        }
    }

    private void requireStorage() {
        if (bucket == null || bucket.isBlank()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "File storage isn't configured on the server (S3_BUCKET)");
        }
    }

    private String publicUrl(String key) {
        return publicEndpoint.replaceAll("/+$", "") + "/" + bucket + "/" + key;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
