package com.example.videolingo.webhook;

import java.util.List;

// The events a webhook can subscribe to.
public final class WebhookEvents {

    public static final String JOB_SUCCEEDED = "job.succeeded";
    public static final String JOB_FAILED = "job.failed";
    public static final String SUBTITLE_REVIEW_REQUESTED = "subtitle.review_requested";
    public static final String SUBTITLE_APPROVED = "subtitle.approved";
    public static final String SUBTITLE_CHANGES_REQUESTED = "subtitle.changes_requested";
    // Only sent by "Send test"; not subscribable.
    public static final String TEST = "webhook.test";

    public static final List<String> ALL = List.of(
            JOB_SUCCEEDED, JOB_FAILED, SUBTITLE_REVIEW_REQUESTED, SUBTITLE_APPROVED, SUBTITLE_CHANGES_REQUESTED);

    private WebhookEvents() {}
}
