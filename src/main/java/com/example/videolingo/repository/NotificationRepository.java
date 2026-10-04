package com.example.videolingo.repository;

import com.example.videolingo.entity.Notification;
import com.example.videolingo.entity.NotificationChannel;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository
        extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    /** Per-batch delivery counts for Notification History. */
    interface BatchCounts {
        Long getBatchId();

        long getTotal();

        long getSent();

        long getFailed();

        long getPending();

        long getRead();

        long getInApp();
    }

    @Query("""
            select n.batchId as batchId, count(n) as total,
                   sum(case when n.status = com.example.videolingo.entity.NotificationStatus.SENT then 1 else 0 end) as sent,
                   sum(case when n.status = com.example.videolingo.entity.NotificationStatus.FAILED then 1 else 0 end) as failed,
                   sum(case when n.status = com.example.videolingo.entity.NotificationStatus.PENDING then 1 else 0 end) as pending,
                   sum(case when n.readAt is not null then 1 else 0 end) as read,
                   sum(case when n.channel = com.example.videolingo.entity.NotificationChannel.IN_APP then 1 else 0 end) as inApp
            from Notification n where n.batchId in :batchIds group by n.batchId
            """)
    List<BatchCounts> countsByBatch(@Param("batchIds") Collection<Long> batchIds);

    Page<Notification> findByRecipientIdAndChannel(Long recipientId, NotificationChannel channel, Pageable pageable);

    Page<Notification> findByRecipientIdAndChannelAndReadAtIsNull(
            Long recipientId, NotificationChannel channel, Pageable pageable);

    long countByRecipientIdAndChannelAndReadAtIsNull(Long recipientId, NotificationChannel channel);

    @Query(
            "select n.id from Notification n where n.recipientId = :userId and n.channel = com.example.videolingo.entity.NotificationChannel.IN_APP and n.readAt is null")
    List<Long> unreadIds(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id in :ids and n.readAt is null")
    int markRead(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);
}
