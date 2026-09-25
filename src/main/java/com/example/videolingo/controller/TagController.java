package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.TagFilterRequest;
import com.example.videolingo.dto.TagRequest;
import com.example.videolingo.dto.TagResponse;
import com.example.videolingo.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// Admin tag management, gated as module "tags" (GET = READ, the rest WRITE).
// Putting tags on videos is done on the video (VideoController /{id}/tags),
// so it's governed by the "videos" permission.
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ResponseEntity<PageResponse<TagResponse>> list(@ModelAttribute TagFilterRequest filter) {
        return ResponseEntity.ok(tagService.list(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TagResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(tagService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TagResponse>> create(@Valid @RequestBody TagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tag created", tagService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TagResponse>> update(@PathVariable Long id, @Valid @RequestBody TagRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Tag updated", tagService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Integer>> delete(@PathVariable Long id) {
        int detached = tagService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(detached == 0 ? "Tag deleted"
                : "Tag deleted and removed from " + detached + (detached == 1 ? " video" : " videos"), detached));
    }
}
