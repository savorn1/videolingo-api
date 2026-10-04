package com.example.videolingo.learn;

import com.example.videolingo.ai.schema.QuizOutput;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiGeneration;
import com.example.videolingo.entity.QuizAttempt;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiGenerationRepository;
import com.example.videolingo.repository.QuizAttemptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Taking AI-generated quizzes: answers are checked here, and each attempt is
// kept so learners (and later, teachers) can see scores over time.
@Service
@RequiredArgsConstructor
public class QuizService {

    public record Submission(@NotNull @Size(max = 50) List<Integer> answers) {}

    public record QuestionResult(
            String question,
            List<String> options,
            int correctOptionIndex,
            Integer chosen,
            boolean correct,
            String explanation,
            int timestampSeconds) {}

    public record AttemptResult(
            Long attemptId,
            Long generationId,
            Long videoId,
            int score,
            int total,
            int percent,
            long attemptNumber,
            List<QuestionResult> questions,
            LocalDateTime createdAt) {}

    public record AttemptSummary(
            Long id, Long generationId, Long videoId, int score, int total, int percent, LocalDateTime createdAt) {}

    private final AiGenerationRepository generationRepository;
    private final QuizAttemptRepository attemptRepository;
    private final LearnService learnService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AttemptResult submit(Long userId, Long generationId, Submission submission, boolean isAdmin) {
        AiGeneration g = generationRepository
                .findById(generationId)
                .filter(x -> x.getType() == AiFeature.QUIZ)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Quiz not found"));
        learnService.requireWatchable(g.getVideoId(), userId, isAdmin);
        QuizOutput quiz = parse(g.getContentJson());
        List<Integer> correct = quiz.questions().stream()
                .map(QuizOutput.QuizQuestion::correctOptionIndex)
                .toList();
        List<Integer> answers = submission.answers();
        int score = QuizGrader.score(correct, answers);
        QuizAttempt attempt = attemptRepository.save(QuizAttempt.builder()
                .userId(userId)
                .generationId(g.getId())
                .videoId(g.getVideoId())
                .score(score)
                .total(correct.size())
                .answers(toJson(answers))
                .build());
        List<QuestionResult> results = new ArrayList<>();
        for (int i = 0; i < quiz.questions().size(); i++) {
            QuizOutput.QuizQuestion q = quiz.questions().get(i);
            Integer chosen = i < answers.size() ? answers.get(i) : null;
            results.add(new QuestionResult(
                    q.question(),
                    q.options(),
                    q.correctOptionIndex(),
                    chosen,
                    chosen != null && chosen == q.correctOptionIndex(),
                    q.explanation(),
                    q.timestampSeconds()));
        }
        return new AttemptResult(
                attempt.getId(),
                g.getId(),
                g.getVideoId(),
                score,
                correct.size(),
                percent(score, correct.size()),
                attemptRepository.countByUserIdAndGenerationId(userId, g.getId()),
                results,
                attempt.getCreatedAt());
    }

    /** The user's attempts — on one video, or their latest 50 overall. */
    @Transactional(readOnly = true)
    public List<AttemptSummary> attempts(Long userId, Long videoId) {
        List<QuizAttempt> rows = videoId != null
                ? attemptRepository.findByUserIdAndVideoIdOrderByCreatedAtDesc(userId, videoId)
                : attemptRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        return rows.stream()
                .map(a -> new AttemptSummary(
                        a.getId(),
                        a.getGenerationId(),
                        a.getVideoId(),
                        a.getScore(),
                        a.getTotal(),
                        percent(a.getScore(), a.getTotal()),
                        a.getCreatedAt()))
                .toList();
    }

    private static int percent(int score, int total) {
        return total == 0 ? 0 : Math.round(score * 100f / total);
    }

    private QuizOutput parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new AppException(HttpStatus.CONFLICT, "This quiz couldn't be read — generate it again");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
