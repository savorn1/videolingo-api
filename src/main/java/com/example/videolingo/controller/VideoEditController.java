package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.VideoEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Trim/crop/scale a range of a video, or split it into segments. Under
// /api/admin/videos, so gated as module "videos": GET = READ, the rest = WRITE.
@RestController
@RequestMapping("/api/admin/videos/{videoId}/edits")
@RequiredArgsConstructor
public class VideoEditController {

    private final VideoEditService editService;

    @GetMapping
    public ResponseEntity<ApiResponse<VideoEditService.EditOverview>> overview(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(editService.overview(videoId)));
    }

    @PostMapping("/trim")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> trim(@PathVariable Long videoId, @RequestBody VideoEditService.TrimRequest request,
                                                                    Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Trim queued", editService.startTrim(videoId, request, requireUsername(authentication))));
    }

    @PostMapping("/split")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> split(@PathVariable Long videoId, @RequestBody VideoEditService.SplitRequest request,
                                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Split queued", editService.startSplit(videoId, request, requireUsername(authentication))));
    }

    @PostMapping("/{clipId}/promote")
    public ResponseEntity<ApiResponse<VideoEditService.PromoteResult>> promote(@PathVariable Long videoId, @PathVariable Long clipId,
                                                                                Authentication authentication) {
        VideoEditService.PromoteResult result = editService.promote(videoId, clipId, requireUsername(authentication));
        String message = "REPLACED".equals(result.kind()) ? "Video replaced" : "New video created";
        return ResponseEntity.ok(ApiResponse.success(message, result));
    }

    @DeleteMapping("/{clipId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long videoId, @PathVariable Long clipId) {
        editService.remove(videoId, clipId);
        return ResponseEntity.ok(ApiResponse.success("Clip discarded", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
