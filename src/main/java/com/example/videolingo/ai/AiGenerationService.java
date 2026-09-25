package com.example.videolingo.ai;

import com.example.videolingo.ai.schema.ChaptersOutput;
import com.example.videolingo.ai.schema.KeyPointsOutput;
import com.example.videolingo.ai.schema.QuestionsOutput;
import com.example.videolingo.ai.schema.QuizOutput;
import com.example.videolingo.ai.schema.SummaryOutput;
import com.example.videolingo.dto.AiGenerateRequest;
import com.example.videolingo.dto.AiGenerationResponse;
import com.example.videolingo.dto.AiUsageDto;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiGeneration;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiGenerationRepository;
import com.example.videolingo.repository.AiUsageRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Generate Summary / Chapters / Key Points / Questions / Quiz. Deliberately
// not @Transactional: the Claude call can take minutes, and no DB transaction
// should be held open across it.
@Service
@RequiredArgsConstructor
public class AiGenerationService {

    private static final long MAX_OUTPUT_TOKENS = 16_000;

    private final AiContextService contextService;
    private final AiClientService aiClient;
    private final AiGenerationRepository generationRepository;
    private final AiUsageRepository usageRepository;
    private final TranscriptRepository transcriptRepository;
    private final LanguageService languageService;
    private final ObjectMapper objectMapper;
    private final SettingsService settings;

    public AiGenerationResponse generate(Long videoId, AiGenerateRequest request, String username) {
        if (request.getType() == AiFeature.CHAT) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Use the chat endpoints for CHAT");
        }
        if (request.getType() == AiFeature.TRANSLATION) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Translations are made by regenerating a transcript, not generated here");
        }
        aiClient.requireReady(request.getType());
        AiContextService.Loaded loaded = contextService.load(videoId, request.getTranscriptId());
        String outputLanguage = request.getOutputLanguage() == null || request.getOutputLanguage().isBlank()
                ? loaded.transcript().getLanguage()
                : languageService.resolve(request.getOutputLanguage(), true);
        Settings.Ai ai = settings.ai();
        int count = request.getCount() != null ? request.getCount() : switch (request.getType()) {
            case KEY_POINTS -> ai.defaultKeyPoints();
            case QUESTIONS -> ai.defaultQuestions();
            default -> ai.defaultQuizQuestions();
        };
        PromptBuilder.AiTask task = PromptBuilder.AiTask.valueOf(request.getType().name());
        String prompt = PromptBuilder.task(task, contextService.languageName(outputLanguage) + " (" + outputLanguage + ")", count);
        var system = PromptBuilder.system(loaded.context());
        var call = new AiClientService.Call(request.getType(), videoId, loaded.transcript().getId(), null, username);
        long durationMs = loaded.video().getDurationSeconds() != null ? loaded.video().getDurationSeconds() * 1000L : loaded.transcript().getDurationMs();

        Object value;
        List<String> warnings;
        int items;
        AiUsageRecord usage;
        switch (request.getType()) {
            case SUMMARY -> {
                var r = aiClient.structured(call, system, prompt, SummaryOutput.class, MAX_OUTPUT_TOKENS);
                var checked = OutputValidator.summary(r.value());
                value = checked.value(); warnings = checked.warnings(); items = checked.value().topics().size(); usage = r.usage();
            }
            case CHAPTERS -> {
                var r = aiClient.structured(call, system, prompt, ChaptersOutput.class, MAX_OUTPUT_TOKENS);
                var checked = OutputValidator.chapters(r.value(), durationMs);
                value = checked.value(); warnings = checked.warnings(); items = checked.value().chapters().size(); usage = r.usage();
            }
            case KEY_POINTS -> {
                var r = aiClient.structured(call, system, prompt, KeyPointsOutput.class, MAX_OUTPUT_TOKENS);
                var checked = OutputValidator.keyPoints(r.value(), durationMs);
                value = checked.value(); warnings = checked.warnings(); items = checked.value().keyPoints().size(); usage = r.usage();
            }
            case QUESTIONS -> {
                var r = aiClient.structured(call, system, prompt, QuestionsOutput.class, MAX_OUTPUT_TOKENS);
                var checked = OutputValidator.questions(r.value(), durationMs);
                value = checked.value(); warnings = checked.warnings(); items = checked.value().questions().size(); usage = r.usage();
            }
            case QUIZ -> {
                var r = aiClient.structured(call, system, prompt, QuizOutput.class, MAX_OUTPUT_TOKENS);
                var checked = OutputValidator.quiz(r.value(), durationMs);
                value = checked.value(); warnings = checked.warnings(); items = checked.value().questions().size(); usage = r.usage();
            }
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "Unsupported type " + request.getType());
        }

        AiGeneration saved = generationRepository.save(AiGeneration.builder()
                .videoId(videoId)
                .transcriptId(loaded.transcript().getId())
                .type(request.getType())
                .outputLanguage(outputLanguage)
                .model(usage.getModel())
                .contentJson(toJson(value))
                .itemCount(items)
                .warnings(warnings.isEmpty() ? null : String.join("\n", warnings))
                .usageId(usage.getId())
                .createdBy(username)
                .build());
        return toResponse(saved, usage);
    }

    public List<AiGenerationResponse> latestForVideo(Long videoId) {
        List<AiGeneration> latest = generationRepository.latestForVideo(videoId);
        Map<Long, AiUsageRecord> usage = usageRepository.findAllById(latest.stream().map(AiGeneration::getUsageId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(java.util.stream.Collectors.toMap(AiUsageRecord::getId, u -> u));
        return latest.stream().map(g -> toResponse(g, usage.get(g.getUsageId()))).toList();
    }

    public PageResponse<AiGenerationResponse> history(Long videoId, AiFeature type, int page, int size) {
        return PageResponse.of(generationRepository.findByVideoIdAndTypeOrderByCreatedAtDesc(videoId, type,
                        PageRequest.of(Math.max(page - 1, 0), Math.max(1, Math.min(size, 50))))
                .map(g -> toResponse(g, g.getUsageId() == null ? null : usageRepository.findById(g.getUsageId()).orElse(null))));
    }

    public AiGenerationResponse get(Long id) {
        AiGeneration g = find(id);
        return toResponse(g, g.getUsageId() == null ? null : usageRepository.findById(g.getUsageId()).orElse(null));
    }

    public void delete(Long id) {
        generationRepository.delete(find(id));
    }

    private AiGeneration find(Long id) {
        return generationRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "AI result not found with id: " + id));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise AI output", e);
        }
    }

    private AiGenerationResponse toResponse(AiGeneration g, AiUsageRecord usage) {
        Map<String, Object> content;
        try {
            content = objectMapper.readValue(g.getContentJson(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            content = Map.of();
        }
        String transcriptLanguage = transcriptRepository.findById(g.getTranscriptId()).map(t -> t.getLanguage()).orElse(null);
        return new AiGenerationResponse(g.getId(), g.getVideoId(), g.getTranscriptId(), transcriptLanguage, g.getType(), g.getOutputLanguage(),
                g.getModel(), content, g.getItemCount(), g.getWarnings() == null ? List.of() : Arrays.asList(g.getWarnings().split("\n")),
                g.getCreatedBy(), g.getCreatedAt(), AiUsageDto.of(usage));
    }
}
