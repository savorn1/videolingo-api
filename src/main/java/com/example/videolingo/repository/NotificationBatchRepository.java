package com.example.videolingo.repository;

import com.example.videolingo.entity.NotificationBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationBatchRepository extends JpaRepository<NotificationBatch, Long>, JpaSpecificationExecutor<NotificationBatch> {

    long countByTemplateId(Long templateId);
}
