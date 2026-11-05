package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService.SectionView;
import com.example.videolingo.streaming.StreamingAdminService;
import com.example.videolingo.streaming.StreamingAdminService.AlertLogRow;
import com.example.videolingo.streaming.StreamingAdminService.Follower;
import com.example.videolingo.streaming.StreamingAdminService.Overview;
import com.example.videolingo.streaming.StreamingAdminService.Report;
import com.example.videolingo.streaming.StreamingAdminService.SeriesDetail;
import com.example.videolingo.streaming.StreamingAdminService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// RuengFlix management, gated as module "streaming" (GET = READ, everything else WRITE). Series
// settings themselves are edited with the collection (CollectionController, `streaming`).
@RestController
@RequestMapping("/api/admin/streaming")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class StreamingAdminController {

    private final StreamingAdminService service;

    public record FeaturedRequest(List<Long> collectionIds) {}

    public record ResolveRequest(boolean resolved) {}

    public record VotesResetRequest(@Min(1) Integer episode) {}

    public record AlertRequest(@Min(1) @Max(100000) Integer episode) {}

    @GetMapping("/series")
    public ResponseEntity<ApiResponse<Overview>> overview() {
        return ResponseEntity.ok(ApiResponse.success(service.overview()));
    }

    @GetMapping("/series/{id}")
    public ResponseEntity<ApiResponse<SeriesDetail>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.detail(id)));
    }

    @PutMapping("/featured")
    public ResponseEntity<ApiResponse<Overview>> featured(@RequestBody FeaturedRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Hero order saved", service.setFeatured(body.collectionIds())));
    }

    @PostMapping("/series/{id}/votes/reset")
    public ResponseEntity<ApiResponse<Integer>> resetVotes(
            @PathVariable long id, @Valid @RequestBody(required = false) VotesResetRequest body) {
        Integer episode = body == null ? null : body.episode();
        int changed = service.resetVotes(id, episode);
        return ResponseEntity.ok(ApiResponse.success(
                episode == null ? "Votes reset for the whole series" : "Votes reset for episode " + episode, changed));
    }

    @GetMapping("/series/{id}/followers")
    public ResponseEntity<ApiResponse<List<Follower>>> followers(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.followers(id)));
    }

    @DeleteMapping("/series/{id}/followers/{chatId}")
    public ResponseEntity<ApiResponse<Void>> removeFollower(@PathVariable long id, @PathVariable long chatId) {
        service.removeFollower(id, chatId);
        return ResponseEntity.ok(ApiResponse.success("Follower removed", null));
    }

    @GetMapping("/series/{id}/alerts")
    public ResponseEntity<ApiResponse<List<AlertLogRow>>> alerts(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.alerts(id)));
    }

    @PostMapping("/series/{id}/alerts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAlert(
            @PathVariable long id, @Valid @RequestBody(required = false) AlertRequest body) {
        Map<String, Object> result = service.sendAlert(id, body == null ? null : body.episode());
        return ResponseEntity.ok(ApiResponse.success("Alert sent", result));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<Report>>> reports(
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(required = false) Long seriesId) {
        return ResponseEntity.ok(ApiResponse.success(service.reports(includeResolved, seriesId)));
    }

    @PutMapping("/reports/{id}")
    public ResponseEntity<ApiResponse<Report>> resolve(
            @PathVariable long id, @RequestBody ResolveRequest body, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                body.resolved() ? "Report resolved" : "Report reopened",
                service.resolve(id, body.resolved(), auth == null ? null : auth.getName())));
    }

    public record ViewerDisableRequest(boolean disabled) {}

    @GetMapping("/viewers")
    public ResponseEntity<ApiResponse<StreamingAdminService.ViewerPage>> viewers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.viewers(q, status, page, size)));
    }

    @PutMapping("/viewers/{id}")
    public ResponseEntity<ApiResponse<StreamingAdminService.ViewerRow>> setViewerDisabled(
            @PathVariable long id, @RequestBody ViewerDisableRequest body) {
        return ResponseEntity.ok(ApiResponse.success(
                body.disabled() ? "Viewer disabled and signed out" : "Viewer enabled",
                service.setViewerDisabled(id, body.disabled())));
    }

    @GetMapping("/prices")
    public ResponseEntity<ApiResponse<SectionView>> prices() {
        return ResponseEntity.ok(ApiResponse.success(service.prices()));
    }

    @PutMapping("/prices")
    public ResponseEntity<ApiResponse<SectionView>> updatePrices(
            @RequestBody Settings.Streaming values, @RequestParam(required = false) Long version, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                "Prices saved", service.updatePrices(values, version, auth == null ? null : auth.getName())));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Status>> status() {
        return ResponseEntity.ok(ApiResponse.success(service.status()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh() {
        return ResponseEntity.ok(ApiResponse.success("RuengFlix refreshed", service.refreshSite()));
    }
}
