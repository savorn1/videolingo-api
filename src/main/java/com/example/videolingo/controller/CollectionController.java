package com.example.videolingo.controller;

import com.example.videolingo.dto.AddCollectionVideosRequest;
import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CollectionFilterRequest;
import com.example.videolingo.dto.CollectionRequest;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.CollectionVideoResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.CollectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin collection management, gated as module "collections" (GET = READ,
// everything else WRITE — including adding/removing videos).
@RestController
@RequestMapping("/api/admin/collections")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class CollectionController {

    private final CollectionService collectionService;

    @GetMapping
    public ResponseEntity<PageResponse<CollectionResponse>> list(@ModelAttribute CollectionFilterRequest filter) {
        return ResponseEntity.ok(collectionService.list(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CollectionResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(collectionService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CollectionResponse>> create(@Valid @RequestBody CollectionRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Collection created", collectionService.create(request, requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CollectionResponse>> update(@PathVariable Long id, @Valid @RequestBody CollectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Collection updated", collectionService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        collectionService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Collection deleted", null));
    }

    // View Collection Videos — in collection order.
    @GetMapping("/{id}/videos")
    public ResponseEntity<PageResponse<CollectionVideoResponse>> videos(@PathVariable Long id,
                                                                         @RequestParam(defaultValue = "1") int page,
                                                                         @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(collectionService.videos(id, page, size));
    }

    @PostMapping("/{id}/videos")
    public ResponseEntity<ApiResponse<Integer>> addVideos(@PathVariable Long id, @Valid @RequestBody AddCollectionVideosRequest request,
                                                          Authentication authentication) {
        int added = collectionService.addVideos(id, request.getVideoIds(), requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success(added == 0 ? "Those videos are already in the collection"
                : "Added " + added + (added == 1 ? " video" : " videos"), added));
    }

    // Full new order of the collection's videos.
    @PutMapping("/{id}/videos/order")
    public ResponseEntity<ApiResponse<Void>> reorder(@PathVariable Long id, @Valid @RequestBody AddCollectionVideosRequest request) {
        collectionService.reorder(id, request.getVideoIds());
        return ResponseEntity.ok(ApiResponse.success("Order saved", null));
    }

    // Remove Video — from the collection only; the video itself is untouched.
    @DeleteMapping("/{id}/videos/{videoId}")
    public ResponseEntity<ApiResponse<CollectionResponse>> removeVideo(@PathVariable Long id, @PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success("Video removed from collection", collectionService.removeVideo(id, videoId)));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
