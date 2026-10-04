package com.example.videolingo.ai;

import com.example.videolingo.entity.Transcript;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.TranscriptSegmentRepository;
import com.example.videolingo.repository.VideoRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Loads a video and the transcript to ground an AI request in.
@Service
@RequiredArgsConstructor
public class AiContextService {

    private final VideoRepository videoRepository;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final LanguageRepository languageRepository;
    private final AiProperties props;

    public record Loaded(Video video, Transcript transcript, PromptBuilder.VideoContext context) {}

    /**
     * Uses {@code transcriptId} when given; otherwise the video's spoken-language
     * transcript, else its longest one. Refuses trashed videos, empty
     * transcripts, and transcripts too long for one request (never truncates).
     */
    @Transactional(readOnly = true)
    public Loaded load(Long videoId, Long transcriptId) {
        Video video = videoRepository
                .findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before using AI on it");
        }
        Transcript transcript = transcriptId != null
                ? transcriptRepository
                        .findById(transcriptId)
                        .filter(t -> t.getVideoId().equals(videoId))
                        .orElseThrow(() -> new AppException(
                                HttpStatus.BAD_REQUEST,
                                "Transcript #" + transcriptId + " isn't a transcript of this video"))
                : pickTranscript(video);
        if (transcript.getSegmentCount() == 0) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "The " + transcript.getLanguage() + " transcript is empty — add text to it first");
        }
        List<PromptBuilder.Segment> segments =
                segmentRepository.findByTranscriptIdOrderByPositionAsc(transcript.getId()).stream()
                        .map(s -> new PromptBuilder.Segment(s.getStartMs(), s.getEndMs(), s.getText(), s.getSpeaker()))
                        .toList();
        long chars = segments.stream().mapToLong(s -> s.text().length()).sum();
        if (chars > props.maxTranscriptChars()) {
            throw new AppException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "This transcript is too long for one AI request (" + chars + " characters; the limit is "
                            + props.maxTranscriptChars() + ")");
        }
        String languageName = languageRepository
                .findByCodeIgnoreCase(transcript.getLanguage())
                .map(l -> l.getName())
                .orElse(transcript.getLanguage());
        return new Loaded(
                video,
                transcript,
                new PromptBuilder.VideoContext(
                        video.getTitle(),
                        languageName,
                        transcript.getLanguage(),
                        video.getDurationSeconds(),
                        segments));
    }

    private Transcript pickTranscript(Video video) {
        List<Transcript> all =
                transcriptRepository.findAll((root, query, cb) -> cb.equal(root.get("videoId"), video.getId()));
        return all.stream()
                .filter(t -> t.getSegmentCount() > 0)
                .min(Comparator.comparing((Transcript t) ->
                                !t.getLanguage().equalsIgnoreCase(String.valueOf(video.getLanguage())))
                        .thenComparing(Comparator.comparingInt(Transcript::getSegmentCount)
                                .reversed()))
                .orElseThrow(() -> new AppException(
                        HttpStatus.CONFLICT,
                        "This video has no transcript with text yet — AI features work from the transcript"));
    }

    public String languageName(String code) {
        return languageRepository
                .findByCodeIgnoreCase(code)
                .map(l -> l.getName())
                .orElse(code);
    }
}
