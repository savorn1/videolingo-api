package com.example.videolingo.pipeline;

import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoDub;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.storage.MediaUrls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Voice-over tracks for a video: what exists, what's being made, and what
// the server can do (which services are configured, which voices exist).
@Service
@RequiredArgsConstructor
public class DubService {

    public record DubResponse(
            Long id,
            Long videoId,
            String language,
            String languageName,
            String voice,
            String voiceName,
            String audioUrl,
            String mimeType,
            long durationMs,
            long sizeBytes,
            Long jobId,
            Long transcriptId,
            boolean locked,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    public record VoiceOption(String id, String name, String gender) {}

    public record DubOverview(
            boolean speechToTextReady,
            boolean textToSpeechReady,
            boolean translationReady,
            String spokenLanguage,
            Map<String, List<VoiceOption>> voices,
            List<DubResponse> dubs,
            List<ProcessingJobResponse> jobs) {}

    public record CreateDubRequest(String language, String voice) {}

    private final VideoRepository videoRepository;
    private final MediaUrls mediaUrls;
    private final VideoDubRepository dubRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobService jobService;
    private final LanguageService languageService;
    private final PipelineProperties props;
    private final Translator translator;
    private final PipelineSteps steps;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DubOverview overview(Long videoId) {
        Video video = findVideo(videoId);
        Map<String, List<VoiceOption>> voices = new LinkedHashMap<>();
        TextToSpeechClient.VOICES.forEach((lang, list) -> voices.put(
                lang,
                list.stream()
                        .map(v -> new VoiceOption(v.id(), v.name(), v.gender()))
                        .toList()));
        List<DubResponse> dubs = dubRepository.findByVideoIdOrderByLanguageAsc(videoId).stream()
                .map(this::toResponse)
                .toList();
        List<ProcessingJobResponse> jobs =
                jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DUB).stream()
                        .map(j -> jobService.getJob(j.getId()))
                        .toList();
        return new DubOverview(
                props.speechToTextReady(),
                props.textToSpeechReady(),
                props.translationReady(),
                video.getLanguage(),
                voices,
                dubs,
                jobs);
    }

    @Transactional
    public ProcessingJobResponse create(Long videoId, CreateDubRequest request, String username) {
        Video video = findVideo(videoId);
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before dubbing it");
        }
        if (!props.textToSpeechReady()) {
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Text-to-speech isn't set up — add OPENAI_API_KEY on the server");
        }
        String language = languageService.resolve(request == null ? null : request.language(), false);
        List<TextToSpeechClient.Voice> voices = TextToSpeechClient.voicesFor(language);
        if (voices.isEmpty()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "There's no voice for " + translator.name(language) + " yet");
        }
        String voice = voices.stream()
                .map(TextToSpeechClient.Voice::id)
                .filter(id -> id.equals(request.voice()))
                .findFirst()
                .orElse(voices.get(0).id());

        for (ProcessingJob j : jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DUB)) {
            if (j.getStatus().isActive()
                    && j.getParameters() != null
                    && j.getParameters().contains("\"language\":\"" + language + "\"")) {
                throw new AppException(
                        HttpStatus.CONFLICT,
                        "A " + translator.name(language) + " dub is already "
                                + j.getStatus().name().toLowerCase() + " (job #" + j.getId() + ")");
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("language", language);
        params.put("voice", voice);
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ProcessingJob job = jobService.enqueue(
                videoId,
                ProcessingJobType.DUB,
                json,
                translator.name(language) + " voice-over (" + voice + ") requested by " + username);
        return jobService.getJob(job.getId());
    }

    /** Audio types an uploaded voice track may be */
    private static final java.util.Set<String> AUDIO_TYPES = java.util.Set.of(
            "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav", "audio/x-wav", "audio/ogg");

    private static final long MAX_UPLOAD_BYTES = 300L * 1024 * 1024;

    /**
     * A voice track made outside VideoLingo (a voice artist, another tool): stored as this video's dub
     * in `language` (Khmer by default), replacing any track there. It must be as long as the video,
     * give or take a few seconds, or it would drift out of step with the picture.
     */
    @Transactional
    public DubResponse upload(
            Long videoId, String language, org.springframework.web.multipart.MultipartFile file, String username) {
        Video video = findVideo(videoId);
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before adding a voice track");
        }
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose an audio file");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new AppException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio files can be up to 300 MB");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!AUDIO_TYPES.contains(mime)) {
            throw new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload an MP3, M4A, AAC, WAV or OGG file");
        }
        String lang = languageService.resolve(language == null || language.isBlank() ? "km" : language, false);
        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("dub-upload-");
            java.nio.file.Path tmp = dir.resolve("track");
            file.transferTo(tmp);
            VideoDub saved = steps.storeUploadedDub(videoId, lang, tmp, mime, username);
            return toResponse(saved);
        } catch (JobFailure e) {
            throw new AppException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (java.io.IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Couldn't read the upload: " + e.getMessage());
        } finally {
            if (dir != null) {
                try (var files = java.nio.file.Files.walk(dir)) {
                    files.sorted(java.util.Comparator.reverseOrder())
                            .forEach(f -> f.toFile().delete());
                } catch (java.io.IOException ignored) {
                    // Temp files: the OS cleans them eventually
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Integer videoSeconds(Long videoId) {
        return findVideo(videoId).getDurationSeconds();
    }

    /** Whether an uploaded track's length is off from the video's by more than a few seconds */
    public static boolean lengthMismatch(Integer videoSeconds, long trackMs) {
        return videoSeconds != null
                && videoSeconds > 0
                && trackMs > 0
                && Math.abs(trackMs / 1000.0 - videoSeconds) > Math.max(5, videoSeconds * 0.05);
    }

    @Transactional
    public void delete(Long videoId, Long dubId) {
        VideoDub dub = findDub(videoId, dubId);
        if (dub.isLocked()) {
            throw new AppException(HttpStatus.CONFLICT, "Unlock this track first");
        }
        dubRepository.delete(dub);
        steps.deleteObject(dub.getStorageKey());
    }

    // Called when the video itself is being permanently purged — every dub
    // goes with it regardless of lock, since there's no video left to unlock it on.
    @Transactional
    public void purgeAllForVideo(Long videoId) {
        for (VideoDub dub : dubRepository.findByVideoIdOrderByLanguageAsc(videoId)) {
            dubRepository.delete(dub);
            steps.deleteObject(dub.getStorageKey());
        }
    }

    @Transactional
    public DubResponse setLocked(Long videoId, Long dubId, boolean locked) {
        VideoDub dub = findDub(videoId, dubId);
        dub.setLocked(locked);
        return toResponse(dubRepository.save(dub));
    }

    private VideoDub findDub(Long videoId, Long dubId) {
        return dubRepository
                .findById(dubId)
                .filter(d -> d.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Voice track not found"));
    }

    private Video findVideo(Long videoId) {
        return videoRepository
                .findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private DubResponse toResponse(VideoDub d) {
        String voiceName = PipelineSteps.UPLOADED_VOICE.equals(d.getVoice())
                ? "Uploaded track"
                : TextToSpeechClient.voicesFor(d.getLanguage()).stream()
                        .filter(v -> v.id().equals(d.getVoice()))
                        .map(TextToSpeechClient.Voice::name)
                        .findFirst()
                        .orElse(d.getVoice());
        return new DubResponse(
                d.getId(),
                d.getVideoId(),
                d.getLanguage(),
                translator.name(d.getLanguage()),
                d.getVoice(),
                voiceName,
                mediaUrls.forBrowser(d.getAudioUrl()),
                d.getMimeType(),
                d.getDurationMs(),
                d.getSizeBytes(),
                d.getJobId(),
                d.getTranscriptId(),
                d.isLocked(),
                d.getCreatedBy(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }
}
