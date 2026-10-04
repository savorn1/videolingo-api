package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CreateTranscriptRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.dto.TranscriptFilterRequest;
import com.example.videolingo.dto.TranscriptResponse;
import com.example.videolingo.dto.TranscriptSearchHit;
import com.example.videolingo.dto.TranscriptSearchRequest;
import com.example.videolingo.dto.UpdateTranscriptRequest;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.revision.RevisionService;
import com.example.videolingo.service.TranscriptService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin transcript management. Gated as module "transcripts" by
// PermissionAuthorizationManager: GETs (incl. search/export) need READ,
// everything else WRITE.
@RestController
@RequestMapping("/api/admin/transcripts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class TranscriptController {

    private final TranscriptService transcriptService;

    @GetMapping
    public ResponseEntity<PageResponse<TranscriptResponse>> list(@ModelAttribute TranscriptFilterRequest filter) {
        return ResponseEntity.ok(transcriptService.listTranscripts(filter));
    }

    // Full-text search across every transcript's segments.
    @GetMapping("/search")
    public ResponseEntity<PageResponse<TranscriptSearchHit>> search(@ModelAttribute TranscriptSearchRequest request) {
        return ResponseEntity.ok(transcriptService.search(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TranscriptResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.getTranscript(id)));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id, @RequestParam(defaultValue = "srt") String format) {
        TranscriptService.ExportedFile file = transcriptService.export(id, format);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TranscriptResponse>> create(
            @Valid @RequestBody CreateTranscriptRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Transcript created",
                        transcriptService.createTranscript(request, requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TranscriptResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateTranscriptRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Transcript saved", transcriptService.updateTranscript(id, request, requireUsername(authentication))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        transcriptService.deleteTranscript(id);
        return ResponseEntity.ok(ApiResponse.success("Transcript deleted", null));
    }

    @GetMapping("/{id}/revisions")
    public ResponseEntity<ApiResponse<List<RevisionService.RevisionSummary>>> revisions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.revisions(id)));
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public ResponseEntity<ApiResponse<RevisionService.RevisionDetail<RevisionService.TranscriptSnapshot>>> revision(
            @PathVariable Long id, @PathVariable Long revisionId) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.revision(id, revisionId)));
    }

    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public ResponseEntity<ApiResponse<TranscriptResponse>> restore(
            @PathVariable Long id,
            @PathVariable Long revisionId,
            @RequestParam(required = false) Long version,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revision restored",
                transcriptService.restoreRevision(id, revisionId, version, requireUsername(authentication))));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<ApiResponse<ProcessingJobResponse>> regenerate(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(
                        "Regeneration queued", transcriptService.regenerate(id, requireUsername(authentication))));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
