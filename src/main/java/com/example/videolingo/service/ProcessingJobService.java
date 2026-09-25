package com.example.videolingo.service;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobFilterRequest;
import com.example.videolingo.dto.ProcessingJobLogResponse;
import com.example.videolingo.dto.ProcessingJobProgressResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;

import java.util.List;
import java.util.Map;

public interface ProcessingJobService {

    PageResponse<ProcessingJobResponse> listJobs(ProcessingJobFilterRequest filter);

    // Job count per status, every status present (zero-filled).
    Map<String, Long> summary();

    ProcessingJobResponse getJob(Long id);

    ProcessingJobProgressResponse getProgress(Long id);

    List<ProcessingJobLogResponse> getLogs(Long id, long afterId, int limit);

    ProcessingJobResponse retry(Long id, String actingUsername);

    ProcessingJobResponse cancel(Long id, String actingUsername);

    void delete(Long id);

    // Queues a new job and writes its first log line (`reason`). Used by
    // other modules that trigger processing, e.g. transcript regeneration.
    ProcessingJob enqueue(Long videoId, ProcessingJobType type, String parametersJson, String reason);
}
