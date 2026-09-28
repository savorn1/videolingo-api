package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.learn.PlaylistService;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// A signed-in learner's own playlists — always private to them, built on the
// same collections the admin side manages.
@RestController
@RequestMapping("/api/me/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    public record CreateRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record AddVideoRequest(@NotNull Long videoId) {
    }

    private final PlaylistService playlistService;
    private final CurrentUserResolver currentUser;

    @GetMapping
    public ResponseEntity<PageResponse<CollectionResponse>> mine(@RequestParam(defaultValue = "1") int page,
                                                                    @RequestParam(defaultValue = "50") int size,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(playlistService.mine(userId(authentication), page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CollectionResponse>> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Playlist created",
                playlistService.create(userId(authentication), request.title(), requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CollectionResponse>> rename(@PathVariable Long id, @Valid @RequestBody RenameRequest request,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Playlist renamed", playlistService.rename(userId(authentication), id, request.title())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication authentication) {
        playlistService.delete(userId(authentication), id);
        return ResponseEntity.ok(ApiResponse.success("Playlist deleted", null));
    }

    @PostMapping("/{id}/videos")
    public ResponseEntity<ApiResponse<CollectionResponse>> addVideo(@PathVariable Long id, @Valid @RequestBody AddVideoRequest request,
                                                                      Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Added to playlist",
                playlistService.addVideo(userId(authentication), id, request.videoId(), requireUsername(authentication))));
    }

    @DeleteMapping("/{id}/videos/{videoId}")
    public ResponseEntity<ApiResponse<CollectionResponse>> removeVideo(@PathVariable Long id, @PathVariable Long videoId, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Removed from playlist", playlistService.removeVideo(userId(authentication), id, videoId)));
    }

    private Long userId(Authentication authentication) {
        return currentUser.requireUserId(authentication);
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
