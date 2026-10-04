package com.example.videolingo.repository;

import com.example.videolingo.entity.Webhook;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByEnabledTrue();

    List<Webhook> findAllByOrderByIdDesc();
}
