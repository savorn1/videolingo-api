package com.example.videolingo.entity;

// One step in a notification's delivery history (NotificationEvent).
public enum NotificationEventType {
    CREATED,
    SENT,
    FAILED,
    RETRIED,
    READ
}
