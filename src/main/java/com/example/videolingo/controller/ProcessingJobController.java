package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobFilterRequest;
import com.example.videolingo.dto.ProcessingJobLogResponse;
import com.example.videolingo.dto.ProcessingJobProgressResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.ProcessingJobService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin view of the video processing pipeline. Gated as module
// "processing-jobs" by PermissionAuthorizationManager: GETs need READ,
// retry/delete need WRITE, and cancel needs APPROVE ("cancel" is one of
// RequestModuleAction's approval keywords).
@RestController
@RequestMapping("/api/admin/processing-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class ProcessingJobController {

    private final ProcessingJobService processingJobService;

    @GetMapping
    public ResponseEntity<PageResponse<ProcessingJobResponse>> list(@ModelAttribute ProcessingJobFilterRequest filter) {
        return ResponseEntity.ok(processingJobService.listJobs(filter));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Long>>> summary() {
        return ResponseEntity.ok(ApiResponse.success(processingJobService.summary()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(processingJobService.getJob(id)));
    }

    @GetMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<ProcessingJobProgressResponse>> progress(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(processingJobService.getProgress(id)));
    }

    // afterId=0 returns from the start; pass the last id you have to tail.
    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<List<ProcessingJobLogResponse>>> logs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "500") int limit) {
        return ResponseEntity.ok(ApiResponse.success(processingJobService.getLogs(id, afterId, limit)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> retry(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success("Job re-queued", processingJobService.retry(id, requireUsername(authentication))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> cancel(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success("Job cancelled", processingJobService.cancel(id, requireUsername(authentication))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        processingJobService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Job deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
