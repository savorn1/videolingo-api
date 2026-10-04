package com.example.videolingo.repository;

import com.example.videolingo.entity.NotificationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    List<NotificationEvent> findByNotificationIdOrderByIdAsc(Long notificationId);
}
