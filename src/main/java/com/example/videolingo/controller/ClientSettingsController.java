package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.pipeline.AudioEditRules;
import com.example.videolingo.pipeline.AudioToVideoRules;
import com.example.videolingo.pipeline.MergeRules;
import com.example.videolingo.pipeline.VideoEditRules;
import com.example.videolingo.pipeline.VideoEditService;
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
            List<String> compactLanguages,
            MediaRules mediaRules) {}

    /**
     * The fixed rules of the media tools (not settings: they change only with a release). The UI keeps its own copies for
     * inline checks; sending them lets it offer only what this server can do (e.g. waveform styles) and notice when its copy
     * is out of step.
     */
    public record MediaRules(
            int maxSegments,
            int maxQueuedEdits,
            int maxAudioClips,
            int maxMergeVideos,
            long maxMergeMs,
            int maxSlides,
            int maxStrip,
            List<String> waveforms) {}

    static final MediaRules MEDIA_RULES = new MediaRules(
            VideoEditRules.MAX_SEGMENTS,
            VideoEditService.MAX_QUEUED_EDITS_PER_VIDEO,
            AudioEditRules.MAX_CLIPS,
            MergeRules.MAX_PARTS,
            MergeRules.MAX_TOTAL_MS,
            AudioToVideoRules.MAX_SLIDES,
            AudioToVideoRules.MAX_STRIP,
            AudioToVideoRules.WAVEFORMS);

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
                t.compactLanguages(),
                MEDIA_RULES)));
    }
}
