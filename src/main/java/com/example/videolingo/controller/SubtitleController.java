package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CreateSubtitleRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.RegenerateSubtitleRequest;
import com.example.videolingo.dto.SubtitleFilterRequest;
import com.example.videolingo.dto.SubtitleResponse;
import com.example.videolingo.dto.SubtitleRulesDto;
import com.example.videolingo.dto.UpdateSubtitleRequest;
import com.example.videolingo.entity.SubtitleKind;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.revision.RevisionService;
import com.example.videolingo.service.SubtitleService;
import com.example.videolingo.service.TranscriptService;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.subtitle.SubtitleRules;
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
import org.springframework.web.multipart.MultipartFile;

// Admin subtitle tracks, gated as module "subtitles" (GET incl. download =
// READ, everything else WRITE).
@RestController
@RequestMapping("/api/admin/subtitles")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class SubtitleController {

    private final SubtitleService subtitleService;
    private final SettingsService settings;

    @GetMapping
    public ResponseEntity<PageResponse<SubtitleResponse>> list(@ModelAttribute SubtitleFilterRequest filter) {
        return ResponseEntity.ok(subtitleService.list(filter));
    }

    // The rules a new track in this language starts with (Settings › Translation).
    @GetMapping("/default-rules")
    public ResponseEntity<ApiResponse<SubtitleRulesDto>> defaultRules(@RequestParam(required = false) String language) {
        SubtitleRules r = settings.subtitleDefaults(language);
        return ResponseEntity.ok(ApiResponse.success(SubtitleRulesDto.builder()
                .maxCharsPerLine(r.maxCharsPerLine())
                .maxLines(r.maxLines())
                .minDurationMs(r.minDurationMs())
                .maxDurationMs(r.maxDurationMs())
                .maxCps(r.maxCps())
                .build()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SubtitleResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(subtitleService.get(id)));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam(defaultValue = "vtt") String format) {
        TranscriptService.ExportedFile file = subtitleService.download(id, format);
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
    public ResponseEntity<ApiResponse<SubtitleResponse>> create(
            @Valid @RequestBody CreateSubtitleRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Subtitle track created", subtitleService.create(request, requireUsername(authentication))));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SubtitleResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("videoId") Long videoId,
            @RequestParam("language") String language,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "kind", required = false) SubtitleKind kind,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Subtitle file uploaded",
                        subtitleService.upload(file, videoId, language, label, kind, requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SubtitleResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateSubtitleRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Subtitle track saved", subtitleService.update(id, request, requireUsername(authentication))));
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<ApiResponse<SubtitleResponse>> setDefault(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Default track set", subtitleService.setDefault(id, requireUsername(authentication))));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<ApiResponse<SubtitleResponse>> regenerate(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RegenerateSubtitleRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Subtitles regenerated", subtitleService.regenerate(id, request, requireUsername(authentication))));
    }

    @GetMapping("/{id}/revisions")
    public ResponseEntity<ApiResponse<List<RevisionService.RevisionSummary>>> revisions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(subtitleService.revisions(id)));
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public ResponseEntity<ApiResponse<RevisionService.RevisionDetail<RevisionService.SubtitleSnapshot>>> revision(
            @PathVariable Long id, @PathVariable Long revisionId) {
        return ResponseEntity.ok(ApiResponse.success(subtitleService.revision(id, revisionId)));
    }

    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public ResponseEntity<ApiResponse<SubtitleResponse>> restore(
            @PathVariable Long id,
            @PathVariable Long revisionId,
            @RequestParam(required = false) Long version,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revision restored",
                subtitleService.restoreRevision(id, revisionId, version, requireUsername(authentication))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        subtitleService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Subtitle track deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
