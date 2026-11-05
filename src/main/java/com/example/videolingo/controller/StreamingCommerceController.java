package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.streaming.StreamingCommerceAdminService;
import com.example.videolingo.streaming.StreamingCommerceAdminService.Badges;
import com.example.videolingo.streaming.StreamingCommerceAdminService.CommentPage;
import com.example.videolingo.streaming.StreamingCommerceAdminService.CommentRow;
import com.example.videolingo.streaming.StreamingCommerceAdminService.Dashboard;
import com.example.videolingo.streaming.StreamingCommerceAdminService.OrderPage;
import com.example.videolingo.streaming.StreamingCommerceAdminService.OrderRow;
import com.example.videolingo.streaming.StreamingCommerceAdminService.ViewerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// RuengFlix orders, comments and the money/retention dashboard — module "streaming": reading needs READ,
// hiding, pinning and replying to comments WRITE; giving coins WRITE; refunding ("reverse") APPROVE; confirming a
// payment ("paid") WRITE and cancelling ("cancel") APPROVE.
@RestController
@RequestMapping("/api/admin/streaming")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class StreamingCommerceController {

    private final StreamingCommerceAdminService service;

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<OrderPage>> orders(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.orders(status, q, page, size)));
    }

    @PostMapping("/orders/{id}/paid")
    public ResponseEntity<ApiResponse<OrderRow>> markPaid(@PathVariable long id, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Payment confirmed", service.markPaid(id, name(auth))));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderRow>> cancel(@PathVariable long id, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Order cancelled", service.cancel(id, name(auth))));
    }

    public record RefundRequest(String reason) {}

    /** Refund a paid order (last segment "reverse": needs APPROVE) */
    @PostMapping("/orders/{id}/reverse")
    public ResponseEntity<ApiResponse<OrderRow>> refund(
            @PathVariable long id, @RequestBody RefundRequest body, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Order refunded", service.refund(id, name(auth), body.reason())));
    }

    public record ReplyRequest(String body) {}

    @PostMapping("/comments/{id}/reply")
    public ResponseEntity<ApiResponse<CommentRow>> reply(
            @PathVariable long id, @RequestBody ReplyRequest body, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Reply posted", service.reply(id, body.body(), name(auth))));
    }

    @DeleteMapping("/comments/{id}/reply")
    public ResponseEntity<ApiResponse<CommentRow>> deleteReply(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success("Reply removed", service.deleteReply(id)));
    }

    @GetMapping("/viewers/{id}/account")
    public ResponseEntity<ApiResponse<ViewerAccount>> account(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.account(id)));
    }

    public record CoinsRequest(Integer coins, String reason) {}

    @PostMapping("/viewers/{id}/coins")
    public ResponseEntity<ApiResponse<ViewerAccount>> coins(
            @PathVariable long id, @RequestBody CoinsRequest body, Authentication auth) {
        if (body.coins() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "How many coins?");
        }
        return ResponseEntity.ok(
                ApiResponse.success("Coins updated", service.grantCoins(id, body.coins(), body.reason(), name(auth))));
    }

    /** Set {@code hidden}, {@code pinned}, or both (hiding wins: a hidden comment is never pinned). */
    public record CommentChange(Boolean hidden, Boolean pinned) {}

    @GetMapping("/comments")
    public ResponseEntity<ApiResponse<CommentPage>> comments(
            @RequestParam(defaultValue = "reported") String filter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.comments(filter, page, size)));
    }

    @PutMapping("/comments/{id}")
    public ResponseEntity<ApiResponse<CommentRow>> change(
            @PathVariable long id, @RequestBody CommentChange body, Authentication auth) {
        if (body.hidden() == null && body.pinned() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Nothing to change");
        }
        CommentRow row = null;
        String message = "";
        if (body.pinned() != null && !Boolean.TRUE.equals(body.hidden())) {
            row = service.setPinned(id, body.pinned(), name(auth));
            message = body.pinned() ? "Comment pinned" : "Comment unpinned";
        }
        if (body.hidden() != null) {
            row = service.setHidden(id, body.hidden(), name(auth));
            message = body.hidden() ? "Comment hidden" : "Comment shown";
        }
        return ResponseEntity.ok(ApiResponse.success(message, row));
    }

    /** Counts for the admin menu: orders waiting and reported comments still showing */
    @GetMapping("/badges")
    public ResponseEntity<ApiResponse<Badges>> badges() {
        return ResponseEntity.ok(ApiResponse.success(service.badges()));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Dashboard>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success(service.dashboard()));
    }

    private static String name(Authentication auth) {
        return auth == null ? null : auth.getName();
    }
}
