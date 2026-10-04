package com.example.videolingo.repository;

import com.example.videolingo.entity.ProcessingJobLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessingJobLogRepository extends JpaRepository<ProcessingJobLog, Long> {

    // Oldest first, only lines newer than afterId — lets a client tail the log.
    @Query("select l from ProcessingJobLog l where l.jobId = :jobId and l.id > :afterId order by l.id asc")
    List<ProcessingJobLog> findAfter(@Param("jobId") Long jobId, @Param("afterId") long afterId, Pageable pageable);

    long countByJobId(Long jobId);

    // Newest line — cheap with the (job_id, id) index.
    Optional<ProcessingJobLog> findFirstByJobIdOrderByIdDesc(Long jobId);

    @Modifying
    @Query("delete from ProcessingJobLog l where l.jobId = :jobId")
    void deleteByJobId(@Param("jobId") Long jobId);
}
