package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.VideoService;
import com.example.videolingo.service.VideoVersionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// A video's file version history. Under /api/admin/videos, so gated as
// module "videos": GET = READ, the rest = WRITE.
@RestController
@RequestMapping("/api/admin/videos/{videoId}/versions")
@RequiredArgsConstructor
public class VideoVersionController {

    private final VideoVersionService versionService;
    private final VideoService videoService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VideoVersionService.VersionResponse>>> list(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(versionService.list(videoId)));
    }

    @PostMapping("/{versionId}/restore")
    public ResponseEntity<ApiResponse<VideoResponse>> restore(
            @PathVariable Long videoId, @PathVariable Long versionId, Authentication authentication) {
        versionService.restore(videoId, versionId, requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success("Version restored", videoService.getVideo(videoId)));
    }

    @DeleteMapping("/{versionId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long videoId, @PathVariable Long versionId) {
        versionService.remove(videoId, versionId);
        return ResponseEntity.ok(ApiResponse.success("Version discarded", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
