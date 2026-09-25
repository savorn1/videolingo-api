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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Voice-over tracks for a video: what exists, what's being made, and what
// the server can do (which services are configured, which voices exist).
@Service
@RequiredArgsConstructor
public class DubService {

    public record DubResponse(Long id, Long videoId, String language, String languageName, String voice, String voiceName,
                              String audioUrl, String mimeType, long durationMs, long sizeBytes, Long jobId, Long transcriptId,
                              String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record VoiceOption(String id, String name, String gender) {
    }

    public record DubOverview(boolean speechToTextReady, boolean textToSpeechReady, boolean translationReady,
                              String spokenLanguage, Map<String, List<VoiceOption>> voices,
                              List<DubResponse> dubs, List<ProcessingJobResponse> jobs) {
    }

    public record CreateDubRequest(String language, String voice) {
    }

    private final VideoRepository videoRepository;
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
        TextToSpeechClient.VOICES.forEach((lang, list) ->
                voices.put(lang, list.stream().map(v -> new VoiceOption(v.id(), v.name(), v.gender())).toList()));
        List<DubResponse> dubs = dubRepository.findByVideoIdOrderByLanguageAsc(videoId).stream().map(this::toResponse).toList();
        List<ProcessingJobResponse> jobs = jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DUB)
                .stream().map(j -> jobService.getJob(j.getId())).toList();
        return new DubOverview(props.speechToTextReady(), props.textToSpeechReady(), props.translationReady(),
                video.getLanguage(), voices, dubs, jobs);
    }

    @Transactional
    public ProcessingJobResponse create(Long videoId, CreateDubRequest request, String username) {
        Video video = findVideo(videoId);
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before dubbing it");
        }
        if (!props.textToSpeechReady()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Text-to-speech isn't set up — add OPENAI_API_KEY on the server");
        }
        String language = languageService.resolve(request == null ? null : request.language(), false);
        List<TextToSpeechClient.Voice> voices = TextToSpeechClient.voicesFor(language);
        if (voices.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "There's no voice for " + translator.name(language) + " yet");
        }
        String voice = voices.stream().map(TextToSpeechClient.Voice::id)
                .filter(id -> id.equals(request.voice())).findFirst().orElse(voices.get(0).id());

        for (ProcessingJob j : jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DUB)) {
            if (j.getStatus().isActive() && j.getParameters() != null && j.getParameters().contains("\"language\":\"" + language + "\"")) {
                throw new AppException(HttpStatus.CONFLICT, "A " + translator.name(language) + " dub is already "
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
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.DUB, json,
                translator.name(language) + " voice-over (" + voice + ") requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public void delete(Long videoId, Long dubId) {
        VideoDub dub = dubRepository.findById(dubId)
                .filter(d -> d.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Voice track not found"));
        dubRepository.delete(dub);
        steps.deleteObject(dub.getStorageKey());
    }

    private Video findVideo(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private DubResponse toResponse(VideoDub d) {
        String voiceName = TextToSpeechClient.voicesFor(d.getLanguage()).stream()
                .filter(v -> v.id().equals(d.getVoice())).map(TextToSpeechClient.Voice::name).findFirst().orElse(d.getVoice());
        return new DubResponse(d.getId(), d.getVideoId(), d.getLanguage(), translator.name(d.getLanguage()), d.getVoice(), voiceName,
                d.getAudioUrl(), d.getMimeType(), d.getDurationMs(), d.getSizeBytes(), d.getJobId(), d.getTranscriptId(),
                d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
