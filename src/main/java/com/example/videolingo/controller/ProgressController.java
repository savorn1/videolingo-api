package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.progress.ProgressService;
import com.example.videolingo.progress.ProgressService.ContinueItem;
import com.example.videolingo.progress.ProgressService.Heartbeat;
import com.example.videolingo.progress.ProgressService.ProgressDto;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The signed-in user's own watch progress — any account, no module permission
// (it only ever touches the caller's rows).
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProgressController {

    public record CompletedRequest(boolean completed) {
    }

    private final ProgressService progressService;
    private final CurrentUserResolver currentUser;

    // The player's heartbeat: every ~15 s while playing, and on pause/end/leave.
    @PutMapping("/progress/{videoId}")
    public ResponseEntity<ApiResponse<ProgressDto>> record(@PathVariable Long videoId, @Valid @RequestBody Heartbeat beat, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(progressService.record(currentUser.requireUserId(authentication), videoId, beat)));
    }

    // ?videoIds=1&videoIds=2 — only videos with progress come back.
    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<List<ProgressDto>>> forVideos(@RequestParam List<Long> videoIds, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(progressService.forVideos(currentUser.requireUserId(authentication), videoIds)));
    }

    @GetMapping("/continue-watching")
    public ResponseEntity<ApiResponse<List<ContinueItem>>> continueWatching(@RequestParam(defaultValue = "12") int limit, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(progressService.continueWatching(currentUser.requireUserId(authentication), currentUser.isAdmin(authentication), limit)));
    }

    @PutMapping("/progress/{videoId}/completed")
    public ResponseEntity<ApiResponse<ProgressDto>> setCompleted(@PathVariable Long videoId, @RequestBody CompletedRequest request,
                                                                 Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(request.completed() ? "Marked as watched" : "Marked as unwatched",
                progressService.setCompleted(currentUser.requireUserId(authentication), videoId, request.completed())));
    }
}
