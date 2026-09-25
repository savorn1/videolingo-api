package com.example.videolingo.repository;

import com.example.videolingo.entity.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByEnabledTrue();

    List<Webhook> findAllByOrderByIdDesc();
}
