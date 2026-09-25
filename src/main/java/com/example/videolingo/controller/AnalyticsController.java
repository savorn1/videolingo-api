package com.example.videolingo.controller;

import com.example.videolingo.analytics.AnalyticsService;
import com.example.videolingo.dto.AnalyticsDtos.AiAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.LanguageAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.StorageAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.TranslationAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.UserAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.VideoAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.WatchAnalytics;
import com.example.videolingo.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// Read-only analytics, gated as module "analytics" (all GET = READ). Every
// endpoint takes an optional ?from=&to= (ISO dates, inclusive; default the
// last 30 days, at most 366) and compares against the period just before.
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class AnalyticsController {

    private final AnalyticsService analytics;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<UserAnalytics>> users(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.users(from, to)));
    }

    @GetMapping("/videos")
    public ResponseEntity<ApiResponse<VideoAnalytics>> videos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.videos(from, to)));
    }

    @GetMapping("/watch")
    public ResponseEntity<ApiResponse<WatchAnalytics>> watch(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.watch(from, to)));
    }

    @GetMapping("/translations")
    public ResponseEntity<ApiResponse<TranslationAnalytics>> translations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.translations(from, to)));
    }

    @GetMapping("/languages")
    public ResponseEntity<ApiResponse<LanguageAnalytics>> languages(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.languages(from, to)));
    }

    @GetMapping("/storage")
    public ResponseEntity<ApiResponse<StorageAnalytics>> storage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.storage(from, to)));
    }

    @GetMapping("/ai")
    public ResponseEntity<ApiResponse<AiAnalytics>> ai(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analytics.ai(from, to)));
    }
}
