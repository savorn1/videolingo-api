package com.example.videolingo.repository;

import com.example.videolingo.entity.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    List<NotificationEvent> findByNotificationIdOrderByIdAsc(Long notificationId);
}
