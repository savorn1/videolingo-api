package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.learn.LearnService;
import com.example.videolingo.learn.QuizService;
import com.example.videolingo.learn.VocabularyService;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The learner side — any signed-in account, no module permissions. Only
// shows what learners are meant to see (see LearnService).
@RestController
@RequestMapping("/api/learn")
@RequiredArgsConstructor
public class LearnController {

    private final LearnService learnService;
    private final QuizService quizService;
    private final VocabularyService vocabularyService;
    private final CurrentUserResolver currentUser;

    @GetMapping("/videos")
    public ResponseEntity<PageResponse<VideoResponse>> videos(@ModelAttribute VideoFilterRequest filter) {
        return ResponseEntity.ok(learnService.videos(filter));
    }

    @GetMapping("/videos/{id}")
    public ResponseEntity<ApiResponse<LearnService.WatchPage>> watch(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(learnService.watch(id)));
    }

    @GetMapping("/videos/{id}/study")
    public ResponseEntity<ApiResponse<List<LearnService.StudyItem>>> study(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(learnService.study(id)));
    }

    @GetMapping("/subtitles/{id}/cues")
    public ResponseEntity<ApiResponse<List<LearnService.Cue>>> cues(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(learnService.cues(id)));
    }

    @GetMapping("/collections")
    public ResponseEntity<PageResponse<CollectionResponse>> collections(@RequestParam(required = false) String search,
                                                                        @RequestParam(defaultValue = "1") int page,
                                                                        @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(learnService.collections(search, page, size));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<ApiResponse<LearnService.LearnCollection>> collection(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(learnService.collection(id, currentUser.requireUserId(authentication), currentUser.isAdmin(authentication))));
    }

    @PostMapping("/quizzes/{generationId}/attempts")
    public ResponseEntity<ApiResponse<QuizService.AttemptResult>> submitQuiz(@PathVariable Long generationId,
                                                                             @Valid @RequestBody QuizService.Submission submission,
                                                                             Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(quizService.submit(currentUser.requireUserId(authentication), generationId, submission)));
    }

    // "What does this word mean?" — from a subtitle cue.
    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<VocabularyService.Lookup>> lookup(@RequestParam String word, @RequestParam String language,
                                                                        @RequestParam String target, @RequestParam(required = false) String context,
                                                                        @RequestParam(required = false) Long videoId, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(vocabularyService.lookup(word, language, target, context, videoId,
                StudyController.requireUsername(authentication))));
    }
}
