package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobLog;
import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

// Runs queued processing jobs in the background, per lane (see JobLane) — one at a time unless configured otherwise. Only the
// types below are handled here; others (TRANSCODE, …) stay queued for
// whatever handles them.
@Component
@RequiredArgsConstructor
@Slf4j
public class JobWorker {

    // public: ProcessingJobServiceImpl reuses it to compute a queued job's position.
    public static final Set<ProcessingJobType> HANDLED = EnumSet.of(ProcessingJobType.TRANSCRIBE, ProcessingJobType.TRANSLATE, ProcessingJobType.DUB,
            ProcessingJobType.DOWNLOAD, ProcessingJobType.EDIT);

    private final JobStore store;
    private final PipelineProperties props;
    private final PipelineSteps steps;
    private final ApplicationEventPublisher events;

    // A pool and a counter of running jobs per lane, so a slow job in one lane doesn't hold up the other. A lane
    // runs one job at a time unless pipeline.media-concurrency / ai-concurrency say otherwise; jobs still start in order.
    private final Map<JobLane, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<JobLane, LaneSlots> slots = new EnumMap<>(JobLane.class);

    {
        for (JobLane lane : JobLane.values()) {
            slots.put(lane, new LaneSlots());
        }
    }

    // Created on first use: the pool size comes from the settings, which aren't available when the fields are set up.
    private ExecutorService executor(JobLane lane) {
        return executors.computeIfAbsent(lane, l -> {
            int size = props.concurrency(l);
            AtomicInteger n = new AtomicInteger();
            return Executors.newFixedThreadPool(size, r -> {
                Thread t = new Thread(r, "job-worker-" + l.name().toLowerCase() + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!props.isEnabled()) {
            log.info("Processing job worker is disabled (pipeline.enabled=false)");
            return;
        }
        int n = store.recoverInterrupted(HANDLED);
        if (n > 0) {
            log.warn("Re-queued {} processing job(s) interrupted by a restart", n);
        }
    }

    // Looks for work in every lane that is free. This is the safety net: after a job finishes the
    // lane looks again straight away (see pollLane), so this mostly matters for a job added while idle.
    @Scheduled(fixedDelayString = "${pipeline.poll-ms:5000}", initialDelayString = "${pipeline.poll-ms:5000}")
    public void poll() {
        for (JobLane lane : JobLane.values()) {
            pollLane(lane);
        }
    }

    // Starts jobs for as long as the lane has a free place and something is queued.
    private void pollLane(JobLane lane) {
        if (!props.isEnabled()) {
            return;
        }
        LaneSlots lanes = slots.get(lane);
        int max = props.concurrency(lane);
        while (lanes.tryAcquire(max)) {
            Optional<ProcessingJob> job;
            try {
                job = store.claimNext(lane.types());
            } catch (RuntimeException e) {
                lanes.release();
                log.error("Couldn't claim a processing job", e);
                return;
            }
            if (job.isEmpty()) {
                lanes.release();
                return;
            }
            executor(lane).submit(() -> {
                try {
                    run(job.get());
                } finally {
                    lanes.release();
                    // The next queued job of this lane starts now instead of at the next poll.
                    try {
                        pollLane(lane);
                    } catch (RuntimeException e) {
                        log.warn("Couldn't look for the next {} job", lane, e);
                    }
                }
            });
        }
    }

    private void run(ProcessingJob job) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory(Path.of(props.workDirectory()), "job-" + job.getId() + "-");
            JobContext ctx = new JobContext(store, job.getId(), dir);
            switch (job.getType()) {
                case TRANSCRIBE -> steps.transcribeJob(job, ctx);
                case TRANSLATE -> steps.translateJob(job, ctx);
                case DUB -> steps.dubJob(job, ctx);
                case DOWNLOAD -> steps.downloadJob(job, ctx);
                case EDIT -> steps.editJob(job, ctx);
                default -> throw new JobFailure("This worker doesn't handle " + job.getType() + " jobs");
            }
            store.finish(job.getId(), ProcessingJobStatus.SUCCEEDED, null);
            store.log(job.getId(), ProcessingJobLog.Level.INFO, "Finished");
            announce(job, ProcessingJobStatus.SUCCEEDED, null);
        } catch (JobCancelled e) {
            store.log(job.getId(), ProcessingJobLog.Level.WARN, "Stopped after cancellation");
        } catch (JobFailure e) {
            store.log(job.getId(), ProcessingJobLog.Level.ERROR, e.getMessage());
            store.finish(job.getId(), ProcessingJobStatus.FAILED, e.getMessage());
            announce(job, ProcessingJobStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("Processing job #{} failed", job.getId(), e);
            String message = "Unexpected error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            store.log(job.getId(), ProcessingJobLog.Level.ERROR, message);
            store.finish(job.getId(), ProcessingJobStatus.FAILED, message);
            announce(job, ProcessingJobStatus.FAILED, message);
        } finally {
            deleteQuietly(dir);
        }
    }

    private void announce(ProcessingJob job, ProcessingJobStatus status, String error) {
        try {
            events.publishEvent(new JobFinishedEvent(job.getId(), job.getVideoId(), job.getType(), status, error));
        } catch (RuntimeException e) {
            log.warn("Couldn't announce the end of processing job #{}", job.getId(), e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp space; the OS cleans up eventually.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
