package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.streaming.StreamingMarketingAdminService;
import com.example.videolingo.streaming.StreamingMarketingAdminService.AdRequest;
import com.example.videolingo.streaming.StreamingMarketingAdminService.AdRow;
import com.example.videolingo.streaming.StreamingMarketingAdminService.ClipRow;
import com.example.videolingo.streaming.StreamingMarketingAdminService.Funnel;
import com.example.videolingo.streaming.StreamingMarketingAdminService.PromotionRequest;
import com.example.videolingo.streaming.StreamingMarketingAdminService.PromotionRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// RuengFlix ads, promotions, shared clips and the paywall funnel — module "streaming": reading needs READ,
// everything else WRITE.
@RestController
@RequestMapping("/api/admin/streaming")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class StreamingMarketingController {

    private final StreamingMarketingAdminService service;

    @GetMapping("/ads")
    public ResponseEntity<ApiResponse<List<AdRow>>> ads() {
        return ResponseEntity.ok(ApiResponse.success(service.ads()));
    }

    @PostMapping("/ads")
    public ResponseEntity<ApiResponse<AdRow>> createAd(@RequestBody AdRequest body, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Ad added", service.createAd(body, name(auth))));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<ApiResponse<AdRow>> updateAd(@PathVariable long id, @RequestBody AdRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Ad saved", service.updateAd(id, body)));
    }

    @DeleteMapping("/ads/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAd(@PathVariable long id) {
        service.deleteAd(id);
        return ResponseEntity.ok(ApiResponse.success("Ad deleted", null));
    }

    @GetMapping("/promotions")
    public ResponseEntity<ApiResponse<List<PromotionRow>>> promotions() {
        return ResponseEntity.ok(ApiResponse.success(service.promotions()));
    }

    @PostMapping("/promotions")
    public ResponseEntity<ApiResponse<PromotionRow>> createPromotion(
            @RequestBody PromotionRequest body, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Promotion scheduled", service.createPromotion(body, name(auth))));
    }

    /** Ends a running promotion now; removes one that hasn't started or is over */
    @DeleteMapping("/promotions/{id}")
    public ResponseEntity<ApiResponse<Void>> stopPromotion(@PathVariable long id) {
        service.stopPromotion(id);
        return ResponseEntity.ok(ApiResponse.success("Promotion stopped", null));
    }

    @GetMapping("/clips")
    public ResponseEntity<ApiResponse<List<ClipRow>>> clips() {
        return ResponseEntity.ok(ApiResponse.success(service.clips()));
    }

    @DeleteMapping("/clips/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteClip(@PathVariable long id) {
        service.deleteClip(id);
        return ResponseEntity.ok(ApiResponse.success("Clip taken down", null));
    }

    @GetMapping("/series/{id}/funnel")
    public ResponseEntity<ApiResponse<Funnel>> funnel(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.funnel(id)));
    }

    private static String name(Authentication auth) {
        return auth == null ? null : auth.getName();
    }
}
