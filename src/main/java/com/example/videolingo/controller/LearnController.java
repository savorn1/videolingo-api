package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.learn.LearnService;
import com.example.videolingo.learn.PronunciationService;
import com.example.videolingo.learn.QuizService;
import com.example.videolingo.learn.VocabularyService;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final PronunciationService pronunciationService;

    // Shadowing: what speech-to-text heard in the learner's recording of a line.
    @PostMapping(value = "/pronunciation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PronunciationService.Heard>> pronunciation(
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String language) {
        return ResponseEntity.ok(ApiResponse.success(pronunciationService.transcribe(file, language)));
    }

    @GetMapping("/videos")
    public ResponseEntity<PageResponse<VideoResponse>> videos(@ModelAttribute VideoFilterRequest filter) {
        return ResponseEntity.ok(learnService.videos(filter));
    }

    @GetMapping("/videos/{id}")
    public ResponseEntity<ApiResponse<LearnService.WatchPage>> watch(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(learnService.watch(
                id, currentUser.requireUserId(authentication), currentUser.isAdmin(authentication))));
    }

    @GetMapping("/videos/{id}/study")
    public ResponseEntity<ApiResponse<List<LearnService.StudyItem>>> study(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(learnService.study(
                id, currentUser.requireUserId(authentication), currentUser.isAdmin(authentication))));
    }

    @GetMapping("/subtitles/{id}/cues")
    public ResponseEntity<ApiResponse<List<LearnService.Cue>>> cues(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                learnService.cues(id, currentUser.requireUserId(authentication), currentUser.isAdmin(authentication))));
    }

    // Glossary terms to highlight in captions (source = the caption's language).
    @GetMapping("/glossary")
    public ResponseEntity<ApiResponse<List<LearnService.GlossaryTerm>>> glossary(
            @RequestParam String source, @RequestParam String target) {
        return ResponseEntity.ok(ApiResponse.success(learnService.glossary(source, target)));
    }

    // "Report a subtitle problem" — becomes a review comment on the track.
    @PostMapping("/subtitles/{id}/report")
    public ResponseEntity<ApiResponse<Void>> report(
            @PathVariable Long id,
            @Valid @RequestBody LearnService.ProblemReport report,
            Authentication authentication) {
        learnService.report(
                id,
                report,
                StudyController.requireUsername(authentication),
                currentUser.requireUserId(authentication),
                currentUser.isAdmin(authentication));
        return ResponseEntity.ok(ApiResponse.success("Thanks — the team will take a look", null));
    }

    @GetMapping("/collections")
    public ResponseEntity<PageResponse<CollectionResponse>> collections(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long videoId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(learnService.collections(search, videoId, page, size));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<ApiResponse<LearnService.LearnCollection>> collection(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(learnService.collection(
                id, currentUser.requireUserId(authentication), currentUser.isAdmin(authentication))));
    }

    @PostMapping("/quizzes/{generationId}/attempts")
    public ResponseEntity<ApiResponse<QuizService.AttemptResult>> submitQuiz(
            @PathVariable Long generationId,
            @Valid @RequestBody QuizService.Submission submission,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(quizService.submit(
                currentUser.requireUserId(authentication),
                generationId,
                submission,
                currentUser.isAdmin(authentication))));
    }

    // "What does this word mean?" — from a subtitle cue.
    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<VocabularyService.Lookup>> lookup(
            @RequestParam String word,
            @RequestParam String language,
            @RequestParam String target,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) Long videoId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(vocabularyService.lookup(
                word, language, target, context, videoId, StudyController.requireUsername(authentication))));
    }
}
