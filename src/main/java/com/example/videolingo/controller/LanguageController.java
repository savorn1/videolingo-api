package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.LanguageFilterRequest;
import com.example.videolingo.dto.LanguageRequest;
import com.example.videolingo.dto.LanguageResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.UpdateLanguageStatusRequest;
import com.example.videolingo.service.LanguageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// Admin language management, gated as module "languages" (GET = READ, the rest
// WRITE). The read-only catalog everyone uses lives at GET /api/languages.
@RestController
@RequestMapping("/api/admin/languages")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class LanguageController {

    private final LanguageService languageService;

    @GetMapping
    public ResponseEntity<PageResponse<LanguageResponse>> list(@ModelAttribute LanguageFilterRequest filter) {
        return ResponseEntity.ok(languageService.list(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LanguageResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(languageService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LanguageResponse>> create(@Valid @RequestBody LanguageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Language created", languageService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LanguageResponse>> update(
            @PathVariable Long id, @Valid @RequestBody LanguageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Language updated", languageService.update(id, request)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<LanguageResponse>> updateStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateLanguageStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                request.getEnabled() ? "Language enabled" : "Language disabled",
                languageService.setEnabled(id, request.getEnabled())));
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<ApiResponse<LanguageResponse>> setDefault(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Default language set", languageService.setDefault(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        languageService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Language deleted", null));
    }
}
