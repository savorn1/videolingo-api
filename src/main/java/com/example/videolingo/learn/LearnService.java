package com.example.videolingo.learn;

import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.CollectionVideoResponse;
import com.example.videolingo.dto.CollectionFilterRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiGeneration;
import com.example.videolingo.entity.CollectionVisibility;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoCollection;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiGenerationRepository;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.SubtitleCueRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.VideoCollectionRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.CollectionService;
import com.example.videolingo.service.VideoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// What learners can see, whatever their permissions: enabled videos that
// aren't in the trash, their published subtitle tracks and voice-overs,
// public collections (and unlisted ones by link, and their own), and the AI
// study material generated for a video — with quiz answers held back until
// the quiz is submitted (QuizService).
@Service
@RequiredArgsConstructor
public class LearnService {

    private static final Set<AiFeature> STUDY_TYPES = Set.of(AiFeature.SUMMARY, AiFeature.CHAPTERS, AiFeature.KEY_POINTS, AiFeature.QUIZ);

    public record Track(Long id, String label, String language, String kind, boolean isDefault) {
    }

    public record VoiceOver(String language, String languageName, String audioUrl) {
    }

    public record WatchPage(VideoResponse video, List<Track> tracks, List<VoiceOver> voiceOvers) {
    }

    public record Cue(long startMs, long endMs, String text) {
    }

    public record StudyItem(Long generationId, AiFeature type, String outputLanguage, Map<String, Object> content, LocalDateTime createdAt) {
    }

    public record LearnCollection(CollectionResponse collection, List<CollectionVideoResponse> videos) {
    }

    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final SubtitleRepository subtitleRepository;
    private final SubtitleCueRepository cueRepository;
    private final VideoDubRepository dubRepository;
    private final LanguageRepository languageRepository;
    private final VideoCollectionRepository collectionRepository;
    private final CollectionService collectionService;
    private final AiGenerationRepository generationRepository;
    private final ObjectMapper objectMapper;

    // ── Videos ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<VideoResponse> videos(VideoFilterRequest filter) {
        filter.setEnabled(true);
        filter.setDeleted(false);
        filter.setOwnerId(null);
        return videoService.listVideos(filter);
    }

    @Transactional(readOnly = true)
    public WatchPage watch(Long videoId) {
        requireWatchable(videoId);
        VideoResponse video = videoService.getVideo(videoId);
        List<Track> tracks = subtitleRepository.findAll((root, q, cb) -> cb.and(cb.equal(root.get("videoId"), videoId), cb.isTrue(root.get("published"))),
                        Sort.by("label")).stream()
                .filter(s -> s.getCueCount() > 0)
                .sorted(Comparator.comparing((Subtitle s) -> !s.isDefault()).thenComparing(Subtitle::getLabel))
                .map(s -> new Track(s.getId(), s.getLabel(), s.getLanguage(), s.getKind().name(), s.isDefault()))
                .toList();
        List<VoiceOver> dubs = dubRepository.findByVideoIdOrderByLanguageAsc(videoId).stream()
                .map(d -> new VoiceOver(d.getLanguage(), languageRepository.findByCodeIgnoreCase(d.getLanguage()).map(l -> l.getName()).orElse(d.getLanguage()),
                        d.getAudioUrl()))
                .toList();
        return new WatchPage(video, tracks, dubs);
    }

    /** Cues of a published track on a watchable video. */
    @Transactional(readOnly = true)
    public List<Cue> cues(Long subtitleId) {
        Subtitle s = subtitleRepository.findById(subtitleId)
                .filter(Subtitle::isPublished)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Subtitle track not found"));
        requireWatchable(s.getVideoId());
        return cueRepository.findBySubtitleIdOrderByPositionAsc(subtitleId).stream().map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText())).toList();
    }

    /** The newest summary, chapters, key points and quiz for each output language. Quiz answers are removed. */
    @Transactional(readOnly = true)
    public List<StudyItem> study(Long videoId) {
        requireWatchable(videoId);
        List<StudyItem> out = new ArrayList<>();
        for (AiGeneration g : generationRepository.latestForVideo(videoId)) {
            if (!STUDY_TYPES.contains(g.getType())) {
                continue;
            }
            Map<String, Object> content = parse(g.getContentJson());
            if (g.getType() == AiFeature.QUIZ) {
                content = withoutAnswers(content);
            }
            out.add(new StudyItem(g.getId(), g.getType(), g.getOutputLanguage(), content, g.getCreatedAt()));
        }
        return out;
    }

    // ── Collections ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CollectionResponse> collections(String search, int page, int size) {
        CollectionFilterRequest filter = new CollectionFilterRequest();
        filter.setSearch(search);
        filter.setVisibility(CollectionVisibility.PUBLIC);
        filter.setPage(page);
        filter.setSize(Math.max(1, Math.min(size, 48)));
        return collectionService.list(filter);
    }

    /** Public and unlisted collections, or the viewer's own (admins: any); only watchable videos are listed. */
    @Transactional(readOnly = true)
    public LearnCollection collection(Long id, Long viewerId, boolean isAdmin) {
        VideoCollection c = collectionRepository.findById(id)
                .filter(x -> isAdmin || x.getVisibility() != CollectionVisibility.PRIVATE || Objects.equals(x.getOwnerId(), viewerId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Collection not found"));
        List<CollectionVideoResponse> videos = collectionService.videos(c.getId(), 1, 500).getData().stream()
                .filter(v -> v.isEnabled() && !v.isDeleted())
                .toList();
        return new LearnCollection(collectionService.get(c.getId()), videos);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    public Video requireWatchable(Long videoId) {
        return videoRepository.findById(videoId)
                .filter(v -> v.isEnabled() && !v.isDeleted())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutAnswers(Map<String, Object> quiz) {
        Map<String, Object> copy = new LinkedHashMap<>(quiz);
        Object questions = quiz.get("questions");
        if (questions instanceof List<?> list) {
            copy.put("questions", list.stream().map(q -> {
                if (!(q instanceof Map<?, ?> m)) {
                    return q;
                }
                Map<String, Object> stripped = new LinkedHashMap<>((Map<String, Object>) m);
                stripped.remove("correctOptionIndex");
                stripped.remove("explanation");
                return stripped;
            }).toList());
        }
        return copy;
    }
}
