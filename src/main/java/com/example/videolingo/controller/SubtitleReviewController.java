package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.SubtitleResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.review.SubtitleReviewService;
import com.example.videolingo.review.SubtitleReviewService.CommentRequest;
import com.example.videolingo.review.SubtitleReviewService.CommentResponse;
import com.example.videolingo.review.SubtitleReviewService.ResolveRequest;
import com.example.videolingo.review.SubtitleReviewService.ReviewRequest;
import com.example.videolingo.security.CurrentUserResolver;
import com.example.videolingo.service.SubtitleService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Review workflow and comments on a subtitle track — module "subtitles".
// submit and comments need WRITE; approve and reject need APPROVE (their
// path's last segment is an approval keyword, see RequestModuleAction).
@RestController
@RequestMapping("/api/admin/subtitles/{id}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class SubtitleReviewController {

    private final SubtitleReviewService reviewService;
    private final SubtitleService subtitleService;
    private final CurrentUserResolver currentUser;

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<SubtitleResponse>> submit(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReviewRequest request,
            Authentication authentication) {
        reviewService.submit(id, request == null ? null : request.note(), requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success("Sent for review", subtitleService.get(id)));
    }

    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<SubtitleResponse>> approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReviewRequest request,
            Authentication authentication) {
        reviewService.approve(
                id,
                request == null ? null : request.note(),
                requireUsername(authentication),
                currentUser.isAdmin(authentication));
        return ResponseEntity.ok(ApiResponse.success("Approved", subtitleService.get(id)));
    }

    // "Request changes" — named reject so it needs the APPROVE permission.
    @PostMapping("/reject")
    public ResponseEntity<ApiResponse<SubtitleResponse>> reject(
            @PathVariable Long id, @Valid @RequestBody ReviewRequest request, Authentication authentication) {
        reviewService.requestChanges(id, request.note(), requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success("Changes requested", subtitleService.get(id)));
    }

    @GetMapping("/comments")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> comments(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.comments(id)));
    }

    @PostMapping("/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable Long id, @Valid @RequestBody CommentRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Comment added", reviewService.addComment(id, request, requireUsername(authentication))));
    }

    @PutMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<CommentResponse>> resolve(
            @PathVariable Long id,
            @PathVariable Long commentId,
            @RequestBody ResolveRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                request.resolved() ? "Comment resolved" : "Comment reopened",
                reviewService.resolveComment(id, commentId, request.resolved(), requireUsername(authentication))));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @PathVariable Long id, @PathVariable Long commentId, Authentication authentication) {
        reviewService.deleteComment(
                id, commentId, requireUsername(authentication), currentUser.isAdmin(authentication));
        return ResponseEntity.ok(ApiResponse.success("Comment deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
