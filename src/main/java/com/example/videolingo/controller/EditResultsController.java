package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.pipeline.VideoEditService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Every edit result still kept, across all videos — the "Edit results" page. Under /videos so it is gated as module
// "videos" (READ); a literal path, matched ahead of "/videos/{id}". Promoting and discarding use the per-video
// endpoints (VideoEditController).
@RestController
@RequestMapping("/api/admin/videos/edit-results")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class EditResultsController {

    private final VideoEditService editService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VideoEditService.ResultRow>>> results() {
        return ResponseEntity.ok(ApiResponse.success(editService.allResults()));
    }
}
