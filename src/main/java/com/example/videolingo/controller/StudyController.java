package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.StudyCard;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.learn.QuizService;
import com.example.videolingo.learn.VocabularyService;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// The signed-in user's own study data: flashcards and quiz scores.
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class StudyController {

    public record KeyPointsRequest(Long generationId) {}

    private final VocabularyService vocabularyService;
    private final QuizService quizService;
    private final CurrentUserResolver currentUser;

    @GetMapping("/cards")
    public ResponseEntity<PageResponse<VocabularyService.CardDto>> cards(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean dueOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        return ResponseEntity.ok(vocabularyService.cards(userId(authentication), search, dueOnly, page, size));
    }

    @GetMapping("/cards/stats")
    public ResponseEntity<ApiResponse<VocabularyService.Stats>> stats(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(vocabularyService.stats(userId(authentication))));
    }

    @GetMapping("/cards/due")
    public ResponseEntity<ApiResponse<List<VocabularyService.CardDto>>> due(
            @RequestParam(defaultValue = "20") int limit, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(vocabularyService.due(userId(authentication), limit)));
    }

    @PostMapping("/cards")
    public ResponseEntity<ApiResponse<VocabularyService.CardDto>> create(
            @Valid @RequestBody VocabularyService.CardRequest request,
            @RequestParam(defaultValue = "WORD") StudyCard.Source source,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Saved to your cards", vocabularyService.create(userId(authentication), request, source)));
    }

    @PostMapping("/cards/from-key-points")
    public ResponseEntity<ApiResponse<Integer>> fromKeyPoints(
            @RequestBody KeyPointsRequest request, Authentication authentication) {
        int added = vocabularyService.fromKeyPoints(
                userId(authentication), request.generationId(), currentUser.isAdmin(authentication));
        return ResponseEntity.ok(ApiResponse.success(
                added == 0 ? "They're all in your cards already" : "Added " + added + " card(s)", added));
    }

    @PutMapping("/cards/{id}")
    public ResponseEntity<ApiResponse<VocabularyService.CardDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody VocabularyService.CardRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success("Card saved", vocabularyService.update(userId(authentication), id, request)));
    }

    @DeleteMapping("/cards/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication authentication) {
        vocabularyService.delete(userId(authentication), id);
        return ResponseEntity.ok(ApiResponse.success("Card deleted", null));
    }

    @PostMapping("/cards/{id}/review")
    public ResponseEntity<ApiResponse<VocabularyService.CardDto>> review(
            @PathVariable Long id,
            @Valid @RequestBody VocabularyService.ReviewRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success(vocabularyService.review(userId(authentication), id, request.grade())));
    }

    @GetMapping("/quiz-attempts")
    public ResponseEntity<ApiResponse<List<QuizService.AttemptSummary>>> attempts(
            @RequestParam(required = false) Long videoId, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(quizService.attempts(userId(authentication), videoId)));
    }

    private Long userId(Authentication authentication) {
        return currentUser.requireUserId(authentication);
    }

    public static String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
