package com.example.videolingo.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.pipeline.JobFinishedEvent;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.VideoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookDispatcherTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatched(ProcessingJobType type, String parameters, ProcessingJobStatus status) {
        WebhookService service = mock(WebhookService.class);
        VideoRepository videos = mock(VideoRepository.class);
        ProcessingJobRepository jobs = mock(ProcessingJobRepository.class);
        when(videos.findById(anyLong())).thenReturn(Optional.empty());
        ProcessingJob job = new ProcessingJob();
        job.setParameters(parameters);
        when(jobs.findById(7L)).thenReturn(Optional.of(job));
        new WebhookDispatcher(service, videos, jobs, new ObjectMapper())
                .onJobFinished(new JobFinishedEvent(7L, 3L, type, status, null));
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(service)
                .dispatch(
                        eq(
                                status == ProcessingJobStatus.SUCCEEDED
                                        ? WebhookEvents.JOB_SUCCEEDED
                                        : WebhookEvents.JOB_FAILED),
                        data.capture());
        return data.getValue();
    }

    @Test
    void editJobsSayWhichEdit() {
        assertEquals(
                "OVERLAY",
                dispatched(
                                ProcessingJobType.EDIT,
                                "{\"operation\":\"OVERLAY\",\"overlay\":{}}",
                                ProcessingJobStatus.SUCCEEDED)
                        .get("operation"));
        assertEquals(
                "AUDIO",
                dispatched(ProcessingJobType.EDIT, "{\"operation\":\"AUDIO\"}", ProcessingJobStatus.FAILED)
                        .get("operation"));
    }

    @Test
    void otherJobsHaveNoOperation() {
        assertFalse(dispatched(ProcessingJobType.DUB, "{\"language\":\"km\"}", ProcessingJobStatus.SUCCEEDED)
                .containsKey("operation"));
    }
}
