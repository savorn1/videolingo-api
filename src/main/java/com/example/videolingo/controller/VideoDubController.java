package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.DubService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Voice-over (dub) tracks of a video. Under /api/admin/videos, so it's gated
// as module "videos": GET = READ, POST/DELETE = WRITE.
@RestController
@RequestMapping("/api/admin/videos/{videoId}/dubs")
@RequiredArgsConstructor
public class VideoDubController {

    private final DubService dubService;

    @GetMapping
    public ResponseEntity<ApiResponse<DubService.DubOverview>> overview(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(dubService.overview(videoId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> create(@PathVariable Long videoId,
                                                                     @RequestBody DubService.CreateDubRequest request,
                                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Voice-over queued", dubService.create(videoId, request, requireUsername(authentication))));
    }

    @DeleteMapping("/{dubId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long videoId, @PathVariable Long dubId) {
        dubService.delete(videoId, dubId);
        return ResponseEntity.ok(ApiResponse.success("Voice track deleted", null));
    }

    public record LockRequest(boolean locked) {
    }

    @PutMapping("/{dubId}/lock")
    public ResponseEntity<ApiResponse<DubService.DubResponse>> setLocked(@PathVariable Long videoId, @PathVariable Long dubId,
                                                                          @RequestBody LockRequest request) {
        return ResponseEntity.ok(ApiResponse.success(request.locked() ? "Track locked" : "Track unlocked",
                dubService.setLocked(videoId, dubId, request.locked())));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
