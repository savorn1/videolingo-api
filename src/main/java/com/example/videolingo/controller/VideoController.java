package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.AssignTagsRequest;
import com.example.videolingo.dto.MoveVideoOwnerRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.UpdateVideoRequest;
import com.example.videolingo.dto.UpdateVideoStatusRequest;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoIngestDtos.ReplaceRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.dto.VideoStatisticsResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.ingest.VideoIngestService;
import com.example.videolingo.service.VideoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin video management. Gated as module "videos" by
// PermissionAuthorizationManager: GETs need READ, everything else WRITE.
@RestController
@RequestMapping("/api/admin/videos")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class VideoController {

    private final VideoService videoService;
    private final VideoIngestService ingestService;

    @GetMapping
    public ResponseEntity<PageResponse<VideoResponse>> list(@ModelAttribute VideoFilterRequest filter) {
        return ResponseEntity.ok(videoService.listVideos(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VideoResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(videoService.getVideo(id)));
    }

    @GetMapping("/{id}/statistics")
    public ResponseEntity<ApiResponse<VideoStatisticsResponse>> statistics(@PathVariable Long id,
                                                                           @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(videoService.getStatistics(id, days)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<VideoResponse>> update(@PathVariable Long id,
                                                             @Valid @RequestBody UpdateVideoRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Video updated", videoService.updateVideo(id, request)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<VideoResponse>> updateStatus(@PathVariable Long id,
                                                                   @Valid @RequestBody UpdateVideoStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(request.getEnabled() ? "Video enabled" : "Video disabled",
                videoService.updateStatus(id, request.getEnabled())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        videoService.deleteVideo(id);
        return ResponseEntity.ok(ApiResponse.success("Video moved to trash", null));
    }

    // Assign Tag to Video — adds one or more existing tags.
    @PostMapping("/{id}/tags")
    public ResponseEntity<ApiResponse<VideoResponse>> assignTags(@PathVariable Long id, @Valid @RequestBody AssignTagsRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Tags added", videoService.assignTags(id, request.getTagIds())));
    }

    // Remove Tag from Video.
    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<ApiResponse<VideoResponse>> removeTag(@PathVariable Long id, @PathVariable Long tagId) {
        return ResponseEntity.ok(ApiResponse.success("Tag removed", videoService.removeTag(id, tagId)));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<VideoResponse>> restore(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Video restored", videoService.restoreVideo(id)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<VideoResponse>> archive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Video archived", videoService.archiveVideo(id)));
    }

    @PostMapping("/{id}/unarchive")
    public ResponseEntity<ApiResponse<VideoResponse>> unarchive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Video unarchived", videoService.unarchiveVideo(id)));
    }

    @PutMapping("/{id}/owner")
    public ResponseEntity<ApiResponse<VideoResponse>> moveOwner(@PathVariable Long id, @Valid @RequestBody MoveVideoOwnerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Video moved", videoService.moveOwner(id, request.getOwnerId())));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<ApiResponse<VideoResponse>> duplicate(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Video duplicated", videoService.duplicate(id, requireUsername(authentication))));
    }

    // Swaps the video's file for a freshly uploaded one; the old file is kept as a version.
    @PostMapping("/{id}/replace")
    public ResponseEntity<ApiResponse<VideoResponse>> replace(@PathVariable Long id, @Valid @RequestBody ReplaceRequest request,
                                                               Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Video file replaced", ingestService.replace(id, request, requireUsername(authentication))));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
