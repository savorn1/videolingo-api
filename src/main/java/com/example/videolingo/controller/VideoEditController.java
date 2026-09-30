package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.OverlayRules;
import com.example.videolingo.pipeline.VideoEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Trim/crop/scale a range of a video, split it into segments, edit or
// extract its sound. Under
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

    @PostMapping("/audio")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> audio(@PathVariable Long videoId, @RequestBody VideoEditService.AudioRequest request,
                                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Audio edit queued", editService.startAudio(videoId, request, requireUsername(authentication))));
    }

    @PostMapping("/extract-audio")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> extractAudio(@PathVariable Long videoId,
                                                                            @RequestBody(required = false) VideoEditService.ExtractRequest request,
                                                                            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Audio extract queued", editService.startExtract(videoId, request, requireUsername(authentication))));
    }

    @PostMapping("/overlay")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> overlay(@PathVariable Long videoId, @RequestBody OverlayRules.Spec request,
                                                                       Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Text & overlay queued", editService.startOverlay(videoId, request, requireUsername(authentication))));
    }

    // Fonts the server can draw text with.
    @GetMapping("/fonts")
    public ResponseEntity<ApiResponse<List<String>>> fonts(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(editService.fonts()));
    }

    // "Auto-center": a crop box for `aspect` (w/h, e.g. 0.5625 for 9:16) centred on the video's own motion.
    @GetMapping("/auto-crop")
    public ResponseEntity<ApiResponse<VideoEditService.CropRect>> autoCrop(@PathVariable Long videoId, @RequestParam double aspect) {
        return ResponseEntity.ok(ApiResponse.success(editService.suggestCrop(videoId, aspect)));
    }

    @GetMapping("/waveform")
    public ResponseEntity<ApiResponse<VideoEditService.Waveform>> waveform(@PathVariable Long videoId, @RequestParam(required = false) String key,
                                                                          @RequestParam(defaultValue = "2000") int points) {
        return ResponseEntity.ok(ApiResponse.success(editService.waveform(videoId, key, points)));
    }

    // Optional body: asNew makes a separate video from a trim/audio/overlay result instead of replacing the original; title names it.
    public record PromoteRequest(Boolean asNew, String title) {
    }

    @PostMapping("/{clipId}/promote")
    public ResponseEntity<ApiResponse<VideoEditService.PromoteResult>> promote(@PathVariable Long videoId, @PathVariable Long clipId,
                                                                                @RequestBody(required = false) PromoteRequest request,
                                                                                Authentication authentication) {
        boolean asNew = request != null && Boolean.TRUE.equals(request.asNew());
        VideoEditService.PromoteResult result = editService.promote(videoId, clipId, requireUsername(authentication), asNew,
                request == null ? null : request.title());
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
