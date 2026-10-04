package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.GlossaryDtos.ApplicableTerm;
import com.example.videolingo.dto.GlossaryDtos.GlossaryFilter;
import com.example.videolingo.dto.GlossaryDtos.GlossaryRequest;
import com.example.videolingo.dto.GlossaryDtos.GlossaryResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.glossary.GlossaryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Translation glossaries, gated as module "glossaries" (GET = READ, the rest WRITE).
@RestController
@RequestMapping("/api/admin/glossaries")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class GlossaryController {

    private final GlossaryService glossaryService;

    @GetMapping
    public ResponseEntity<PageResponse<GlossaryResponse>> list(@ModelAttribute GlossaryFilter filter) {
        return ResponseEntity.ok(glossaryService.list(filter));
    }

    // The merged terms that apply when translating into `target` (from
    // `source`, if given) — what the subtitle editor checks cues against.
    @GetMapping("/terms")
    public ResponseEntity<ApiResponse<List<ApplicableTerm>>> terms(
            @RequestParam String target, @RequestParam(required = false) String source) {
        return ResponseEntity.ok(ApiResponse.success(glossaryService.applicable(source, target)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GlossaryResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(glossaryService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GlossaryResponse>> create(
            @Valid @RequestBody GlossaryRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Glossary created", glossaryService.create(request, requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<GlossaryResponse>> update(
            @PathVariable Long id, @Valid @RequestBody GlossaryRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Glossary saved", glossaryService.update(id, request, requireUsername(authentication))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        glossaryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Glossary deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
