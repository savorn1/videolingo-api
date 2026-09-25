package com.example.videolingo.repository;

import com.example.videolingo.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findByWebhookIdOrderByIdDesc(Long webhookId, Pageable pageable);

    // Deletes all but the newest `keep` deliveries of a webhook.
    @Modifying
    @Transactional
    @Query(value = """
            delete from webhook_deliveries where webhook_id = :webhookId and id < (
              select coalesce(min(id), 0) from (
                select id from webhook_deliveries where webhook_id = :webhookId order by id desc limit :keep
              ) newest)
            """, nativeQuery = true)
    int prune(@Param("webhookId") Long webhookId, @Param("keep") int keep);

    @Modifying
    @Transactional
    @Query("delete from WebhookDelivery d where d.webhookId = :webhookId")
    void deleteByWebhookId(@Param("webhookId") Long webhookId);
}
