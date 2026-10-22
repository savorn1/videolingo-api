package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The storage report and the clean-up of old versions. Under /videos so it is gated as module "videos": the report
// is READ, the clean-up WRITE; a literal path, matched ahead of "/videos/{id}".
@RestController
@RequestMapping("/api/admin/videos/storage")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class StorageController {

    private final StorageService storage;

    public record PruneRequest(int keepLatest, int olderThanDays) {}

    @GetMapping
    public ResponseEntity<ApiResponse<StorageService.Report>> report() {
        return ResponseEntity.ok(ApiResponse.success(storage.report()));
    }

    @PostMapping("/prune-versions")
    public ResponseEntity<ApiResponse<StorageService.PruneResult>> pruneVersions(@RequestBody PruneRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Old versions removed", storage.pruneVersions(request.keepLatest(), request.olderThanDays())));
    }
}
