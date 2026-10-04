package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.settings.SettingsService.Section;
import com.example.videolingo.settings.SettingsService.SectionView;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

// Application settings, gated as module "settings" (GET = READ, PUT/DELETE =
// WRITE). Each section can be read and replaced on its own; GET/PUT on the
// root cover every section at once. Updates take the section's `version`
// (from the last read; -1 if it was on defaults) to refuse overwriting a
// newer change.
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class SettingsController {

    private final SettingsService settings;

    /** Everything that PUT /api/admin/settings accepts; omitted sections are left alone. */
    public record AllSettingsUpdate(
            Settings.General general,
            Settings.Video video,
            Settings.Translation translation,
            Settings.Ai ai,
            Settings.Storage storage,
            // section → version it was read at
            Map<String, Long> versions) {}

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, SectionView>>> all() {
        Map<String, SectionView> out = new LinkedHashMap<>();
        settings.all().forEach(v -> out.put(v.section(), v));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, SectionView>>> updateAll(
            @RequestBody AllSettingsUpdate body, Authentication auth) {
        Map<String, Long> versions = body.versions() == null ? Map.of() : body.versions();
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("general", body.general());
        given.put("video", body.video());
        given.put("translation", body.translation());
        given.put("ai", body.ai());
        given.put("storage", body.storage());
        Map<String, SectionView> out = new LinkedHashMap<>();
        given.forEach((key, values) -> {
            if (values != null) {
                out.put(key, settings.update(Section.of(key), values, versions.get(key), username(auth)));
            }
        });
        if (out.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nothing to update — include at least one section");
        }
        return ResponseEntity.ok(ApiResponse.success("Settings saved", out));
    }

    @GetMapping("/{section}")
    public ResponseEntity<ApiResponse<SectionView>> get(@PathVariable String section) {
        return ResponseEntity.ok(ApiResponse.success(settings.view(Section.of(section))));
    }

    @PutMapping("/general")
    public ResponseEntity<ApiResponse<SectionView>> general(
            @RequestBody Settings.General values, @RequestParam(required = false) Long version, Authentication auth) {
        return saved(settings.update(Section.GENERAL, values, version, username(auth)));
    }

    @PutMapping("/video")
    public ResponseEntity<ApiResponse<SectionView>> video(
            @RequestBody Settings.Video values, @RequestParam(required = false) Long version, Authentication auth) {
        return saved(settings.update(Section.VIDEO, values, version, username(auth)));
    }

    @PutMapping("/translation")
    public ResponseEntity<ApiResponse<SectionView>> translation(
            @RequestBody Settings.Translation values,
            @RequestParam(required = false) Long version,
            Authentication auth) {
        return saved(settings.update(Section.TRANSLATION, values, version, username(auth)));
    }

    @PutMapping("/ai")
    public ResponseEntity<ApiResponse<SectionView>> ai(
            @RequestBody Settings.Ai values, @RequestParam(required = false) Long version, Authentication auth) {
        return saved(settings.update(Section.AI, values, version, username(auth)));
    }

    @PutMapping("/storage")
    public ResponseEntity<ApiResponse<SectionView>> storage(
            @RequestBody Settings.Storage values, @RequestParam(required = false) Long version, Authentication auth) {
        return saved(settings.update(Section.STORAGE, values, version, username(auth)));
    }

    /** Restore a section's built-in defaults. */
    @DeleteMapping("/{section}")
    public ResponseEntity<ApiResponse<SectionView>> reset(@PathVariable String section) {
        return ResponseEntity.ok(ApiResponse.success("Defaults restored", settings.reset(Section.of(section))));
    }

    private static ResponseEntity<ApiResponse<SectionView>> saved(SectionView view) {
        return ResponseEntity.ok(ApiResponse.success("Settings saved", view));
    }

    private static String username(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return auth.getName();
    }
}
