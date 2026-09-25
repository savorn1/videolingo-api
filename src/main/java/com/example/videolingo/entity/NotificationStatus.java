package com.example.videolingo.entity;

// Delivery state. In-app notifications are SENT as soon as they land in the
// inbox; emails stay PENDING until the SMTP send finishes (SENT or FAILED).
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
