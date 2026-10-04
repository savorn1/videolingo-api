package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.VideoMarkerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Named timestamps on a video's timeline. Under /api/admin/videos, so gated
// as module "videos": GET = READ, the rest = WRITE.
@RestController
@RequestMapping("/api/admin/videos/{videoId}/markers")
@RequiredArgsConstructor
public class VideoMarkerController {

    private final VideoMarkerService markerService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VideoMarkerService.MarkerResponse>>> list(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(markerService.list(videoId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VideoMarkerService.MarkerResponse>> create(
            @PathVariable Long videoId,
            @RequestBody VideoMarkerService.MarkerRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Marker added", markerService.create(videoId, request, requireUsername(authentication))));
    }

    @PutMapping("/{markerId}")
    public ResponseEntity<ApiResponse<VideoMarkerService.MarkerResponse>> update(
            @PathVariable Long videoId,
            @PathVariable Long markerId,
            @RequestBody VideoMarkerService.MarkerRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Marker updated", markerService.update(videoId, markerId, request)));
    }

    @DeleteMapping("/{markerId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long videoId, @PathVariable Long markerId) {
        markerService.remove(videoId, markerId);
        return ResponseEntity.ok(ApiResponse.success("Marker deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
