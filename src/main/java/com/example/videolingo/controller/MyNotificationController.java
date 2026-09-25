package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.NotificationDtos.InboxCount;
import com.example.videolingo.dto.NotificationDtos.NotificationResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// The signed-in user's in-app inbox (the bell). Any authenticated account,
// no module permission needed — it only ever touches the caller's own rows.
@RestController
@RequestMapping("/api/users/me/notifications")
@RequiredArgsConstructor
public class MyNotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                                   @RequestParam(defaultValue = "1") int page,
                                                                   @RequestParam(defaultValue = "10") int size,
                                                                   Authentication authentication) {
        return ResponseEntity.ok(notificationService.inbox(authentication.getName(), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<InboxCount>> unreadCount(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(new InboxCount(notificationService.unreadCount(authentication.getName()))));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.markRead(id, authentication.getName())));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Integer>> markAllRead(Authentication authentication) {
        int n = notificationService.markAllRead(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(n == 0 ? "Nothing unread" : "Marked " + n + " as read", n));
    }
}
