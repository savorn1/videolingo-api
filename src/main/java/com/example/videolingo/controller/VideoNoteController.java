package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.review.VideoNoteService;
import com.example.videolingo.review.VideoNoteService.NoteRequest;
import com.example.videolingo.review.VideoNoteService.NoteResponse;
import com.example.videolingo.review.VideoNoteService.ResolveRequest;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Review notes on a video — module "videos": reading needs READ, writing WRITE.
@RestController
@RequestMapping("/api/admin/videos/{id}/notes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class VideoNoteController {

    private final VideoNoteService noteService;
    private final CurrentUserResolver currentUser;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NoteResponse>>> notes(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(noteService.notes(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<NoteResponse>> add(
            @PathVariable Long id, @Valid @RequestBody NoteRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Note added", noteService.addNote(id, request, requireUsername(authentication))));
    }

    @PutMapping("/{noteId}")
    public ResponseEntity<ApiResponse<NoteResponse>> resolve(
            @PathVariable Long id,
            @PathVariable Long noteId,
            @RequestBody ResolveRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                request.resolved() ? "Note resolved" : "Note reopened",
                noteService.resolveNote(id, noteId, request.resolved(), requireUsername(authentication))));
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id, @PathVariable Long noteId, Authentication authentication) {
        noteService.deleteNote(id, noteId, requireUsername(authentication), currentUser.isAdmin(authentication));
        return ResponseEntity.ok(ApiResponse.success("Note deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
