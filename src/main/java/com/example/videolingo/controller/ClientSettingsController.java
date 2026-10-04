package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The non-sensitive settings the admin UI needs on ordinary pages (limits to
// show before a request fails, defaults to pre-fill). Any signed-in account —
// no "settings" permission needed.
@RestController
@RequestMapping("/api/settings/client")
@RequiredArgsConstructor
public class ClientSettingsController {

    private final SettingsService settings;

    public record ClientSettings(
            String siteName,
            int maxTagsPerVideo,
            int maxCategoriesPerVideo,
            boolean requireCategory,
            int statisticsDefaultDays,
            int maxVideoUploadMb,
            List<String> defaultTargetLanguages,
            boolean blockPublishWithIssues,
            boolean requireApprovalToPublish,
            int maxUploadMb,
            List<String> allowedUploadTypes,
            int maxSubtitleUploadMb,
            // Starting rules for new subtitle tracks (compactRules for compactLanguages).
            Settings.SubtitleRulesSetting standardRules,
            Settings.SubtitleRulesSetting compactRules,
            List<String> compactLanguages) {}

    @GetMapping
    public ResponseEntity<ApiResponse<ClientSettings>> get() {
        Settings.Video v = settings.video();
        Settings.Translation t = settings.translation();
        Settings.Storage s = settings.storage();
        return ResponseEntity.ok(ApiResponse.success(new ClientSettings(
                settings.general().siteName(),
                v.maxTagsPerVideo(),
                v.maxCategoriesPerVideo(),
                v.requireCategory(),
                v.statisticsDefaultDays(),
                v.maxVideoUploadMb(),
                t.defaultTargetLanguages(),
                t.blockPublishWithIssues(),
                t.requireApprovalToPublish(),
                s.maxUploadMb(),
                s.allowedUploadTypes(),
                s.maxSubtitleUploadMb(),
                t.standardRules(),
                t.compactRules(),
                t.compactLanguages())));
    }
}
