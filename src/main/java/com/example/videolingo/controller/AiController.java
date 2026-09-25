package com.example.videolingo.controller;

import com.example.videolingo.ai.AiChatService;
import com.example.videolingo.ai.AiEstimateService;
import com.example.videolingo.ai.AiGenerationService;
import com.example.videolingo.ai.AiUsageService;
import com.example.videolingo.dto.AiChatDtos;
import com.example.videolingo.dto.AiGenerateRequest;
import com.example.videolingo.dto.AiGenerationResponse;
import com.example.videolingo.dto.AiStatusResponse;
import com.example.videolingo.dto.AiUsageDto;
import com.example.videolingo.dto.AiUsageFilterRequest;
import com.example.videolingo.dto.AiUsageSummaryResponse;
import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.exception.AppException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// AI features, gated as module "ai" by PermissionAuthorizationManager: GETs
// (results, chats, usage) need READ; generating, chatting and deleting need
// WRITE. Every call that reaches Claude is recorded in ai_usage.
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class AiController {

    private final AiGenerationService generationService;
    private final AiChatService chatService;
    private final AiUsageService usageService;
    private final AiEstimateService estimateService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<AiStatusResponse>> status() {
        return ResponseEntity.ok(ApiResponse.success(usageService.status()));
    }

    // ── Generate Summary / Chapters / Key Points / Questions / Quiz ───────

    @PostMapping("/videos/{videoId}/generate")
    public ResponseEntity<ApiResponse<AiGenerationResponse>> generate(@PathVariable Long videoId, @Valid @RequestBody AiGenerateRequest request,
                                                                      Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Generated", generationService.generate(videoId, request, username(authentication))));
    }

    // Newest result per (type, output language) for the video.
    // Expected tokens and cost of a generation, before running it.
    @GetMapping("/videos/{videoId}/estimate")
    public ResponseEntity<ApiResponse<AiEstimateService.Estimate>> estimate(@PathVariable Long videoId, @RequestParam AiFeature type,
                                                                           @RequestParam(required = false) Long transcriptId,
                                                                           @RequestParam(required = false) Integer count) {
        return ResponseEntity.ok(ApiResponse.success(estimateService.estimate(videoId, type, transcriptId, count)));
    }

    @GetMapping("/videos/{videoId}/generations")
    public ResponseEntity<ApiResponse<List<AiGenerationResponse>>> latest(@PathVariable Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(generationService.latestForVideo(videoId)));
    }

    @GetMapping("/videos/{videoId}/generations/history")
    public ResponseEntity<PageResponse<AiGenerationResponse>> history(@PathVariable Long videoId, @RequestParam AiFeature type,
                                                                      @RequestParam(defaultValue = "1") int page,
                                                                      @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(generationService.history(videoId, type, page, size));
    }

    @GetMapping("/generations/{id}")
    public ResponseEntity<ApiResponse<AiGenerationResponse>> generation(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(generationService.get(id)));
    }

    @DeleteMapping("/generations/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteGeneration(@PathVariable Long id) {
        generationService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }

    // ── AI Chat ───────────────────────────────────────────────────────────

    @GetMapping("/chats")
    public ResponseEntity<ApiResponse<List<AiChatDtos.ChatDto>>> chats(@RequestParam Long videoId) {
        return ResponseEntity.ok(ApiResponse.success(chatService.listForVideo(videoId)));
    }

    @PostMapping("/chats")
    public ResponseEntity<ApiResponse<AiChatDtos.ChatDto>> createChat(@Valid @RequestBody AiChatDtos.CreateChatRequest request,
                                                                      Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Chat started",
                chatService.create(request.getVideoId(), request.getTranscriptId(), username(authentication))));
    }

    @GetMapping("/chats/{id}")
    public ResponseEntity<ApiResponse<AiChatDtos.ChatDto>> chat(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(chatService.get(id)));
    }

    @PostMapping("/chats/{id}/messages")
    public ResponseEntity<ApiResponse<AiChatDtos.TurnDto>> send(@PathVariable Long id, @Valid @RequestBody AiChatDtos.SendMessageRequest request,
                                                                Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(chatService.send(id, request.getContent(), username(authentication))));
    }

    @DeleteMapping("/chats/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteChat(@PathVariable Long id) {
        chatService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Chat deleted", null));
    }

    // ── AI Usage Tracking / AI Cost Tracking ──────────────────────────────

    @GetMapping("/usage")
    public ResponseEntity<PageResponse<AiUsageDto>> usage(@ModelAttribute AiUsageFilterRequest filter) {
        return ResponseEntity.ok(usageService.list(filter));
    }

    @GetMapping("/usage/summary")
    public ResponseEntity<ApiResponse<AiUsageSummaryResponse>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(usageService.summary(from, to)));
    }

    private static String username(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
