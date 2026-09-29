package com.example.videolingo.webhook;

import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.videolingo.entity.Video;
import com.example.videolingo.pipeline.JobFinishedEvent;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.review.SubtitleReviewService.SubtitleReviewEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

// Turns application events into webhook messages. AFTER_COMMIT so nothing is
// announced for a change that then rolls back; fallbackExecution for events
// published outside a transaction (the job worker). @Async so a slow
// receiver never holds up a request or the job worker.
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private final WebhookService webhookService;
    private final VideoRepository videoRepository;
    private final ProcessingJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    private String operation(String parameters) {
        try {
            return parameters == null ? null : objectMapper.readTree(parameters).path("operation").asText(null);
        } catch (Exception ex) {
            return null;
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobFinished(JobFinishedEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", e.jobId());
        data.put("type", e.type().name());
        if (e.type() == ProcessingJobType.EDIT) {
            // Which edit: TRIM, SPLIT, AUDIO, EXTRACT or OVERLAY.
            data.put("operation", jobRepository.findById(e.jobId()).map(j -> operation(j.getParameters())).orElse(null));
        }
        data.put("status", e.status().name());
        data.put("videoId", e.videoId());
        data.put("videoTitle", videoRepository.findById(e.videoId()).map(Video::getTitle).orElse(null));
        data.put("error", e.errorMessage());
        send(e.status() == ProcessingJobStatus.SUCCEEDED ? WebhookEvents.JOB_SUCCEEDED : WebhookEvents.JOB_FAILED, data);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSubtitleReview(SubtitleReviewEvent e) {
        String event = switch (e.status()) {
            case IN_REVIEW -> WebhookEvents.SUBTITLE_REVIEW_REQUESTED;
            case APPROVED -> WebhookEvents.SUBTITLE_APPROVED;
            case CHANGES_REQUESTED -> WebhookEvents.SUBTITLE_CHANGES_REQUESTED;
            case DRAFT -> null;
        };
        if (event == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subtitleId", e.subtitleId());
        data.put("videoId", e.videoId());
        data.put("label", e.label());
        data.put("language", e.language());
        data.put("reviewStatus", e.status().name());
        data.put("by", e.actor());
        data.put("note", e.note());
        send(event, data);
    }

    private void send(String event, Map<String, Object> data) {
        try {
            webhookService.dispatch(event, data);
        } catch (RuntimeException ex) {
            log.warn("Couldn't deliver webhook event {}", event, ex);
        }
    }
}
