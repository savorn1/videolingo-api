package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.VideoDownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Download a video as MP4 / import a link video into storage. Under
// /api/admin/videos, so gated as module "videos": GET = READ, the rest = WRITE.
@RestController
@RequestMapping("/api/admin/videos/{videoId}/downloads")
@RequiredArgsConstructor
public class VideoDownloadController {

    private final VideoDownloadService downloadService;

    @GetMapping
    public ResponseEntity<ApiResponse<VideoDownloadService.DownloadOverview>> overview(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(downloadService.overview(videoId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> start(
            @PathVariable Long videoId,
            @RequestBody VideoDownloadService.DownloadRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(
                        "Download queued", downloadService.start(videoId, request, requireUsername(authentication))));
    }

    @DeleteMapping("/{exportId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long videoId, @PathVariable Long exportId) {
        downloadService.deleteExport(videoId, exportId);
        return ResponseEntity.ok(ApiResponse.success("Download deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
