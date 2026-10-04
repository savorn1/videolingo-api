package com.example.videolingo.repository;

import com.example.videolingo.entity.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    @Query("select distinct a.module from AuditLog a order by a.module")
    List<String> modules();

    @Modifying
    @Query("delete from AuditLog a where a.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
