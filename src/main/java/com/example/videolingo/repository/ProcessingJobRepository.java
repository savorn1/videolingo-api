package com.example.videolingo.repository;

import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long>, JpaSpecificationExecutor<ProcessingJob> {

    interface StatusCount {
        ProcessingJobStatus getStatus();
        long getCount();
    }

    @Query("select j.status as status, count(j) as count from ProcessingJob j group by j.status")
    List<StatusCount> countByStatus();

    // Job worker (pipeline.JobStore).
    Optional<ProcessingJob> findFirstByStatusAndTypeInOrderByIdAsc(ProcessingJobStatus status, Collection<ProcessingJobType> types);

    List<ProcessingJob> findByStatusAndTypeIn(ProcessingJobStatus status, Collection<ProcessingJobType> types);

    List<ProcessingJob> findTop10ByVideoIdAndTypeOrderByIdDesc(Long videoId, ProcessingJobType type);

    long countByStatusAndTypeInAndIdLessThan(ProcessingJobStatus status, java.util.Collection<ProcessingJobType> types, Long id);

    /** Active (queued or running) jobs of these types for one video, oldest first — how many an admin already has in flight. */
    List<ProcessingJob> findByVideoIdAndTypeInAndStatusInOrderByIdAsc(Long videoId, java.util.Collection<ProcessingJobType> types,
            java.util.Collection<ProcessingJobStatus> statuses);
}
