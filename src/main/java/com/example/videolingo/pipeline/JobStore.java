package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobLog;
import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.repository.ProcessingJobLogRepository;
import com.example.videolingo.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

// The worker's side of the job contract (see ProcessingJob): claim, report
// progress, finish. Each call is its own short transaction, and every write
// re-reads the job first so an admin's cancel is never overwritten.
@Component
@RequiredArgsConstructor
public class JobStore {

    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobLogRepository logRepository;

    /** Takes the oldest queued job of these types, or empty when there's none (or another worker won the race). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProcessingJob> claimNext(Collection<ProcessingJobType> types) {
        Optional<ProcessingJob> next = jobRepository.findFirstByStatusAndTypeInOrderByIdAsc(ProcessingJobStatus.QUEUED, types);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        ProcessingJob job = next.get();
        job.setStatus(ProcessingJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job.setFinishedAt(null);
        job.setErrorMessage(null);
        job.setProgress(0);
        job.setCurrentStep("Starting");
        job.setAttempts(job.getAttempts() + 1);
        try {
            jobRepository.saveAndFlush(job);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Optional.empty();
        }
        log(job.getId(), ProcessingJobLog.Level.INFO, "Started (attempt " + job.getAttempts() + " of " + job.getMaxAttempts() + ")");
        return Optional.of(job);
    }

    /** Updates a running job; throws {@link JobCancelled} if it was cancelled meanwhile. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long jobId, Consumer<ProcessingJob> change) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ProcessingJob job = jobRepository.findById(jobId).orElseThrow(JobCancelled::new);
            if (job.getStatus() != ProcessingJobStatus.RUNNING) {
                throw new JobCancelled();
            }
            change.accept(job);
            try {
                jobRepository.saveAndFlush(job);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                // An admin touched the job between our read and write — look again.
            }
        }
        throw new JobCancelled();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long jobId, ProcessingJobStatus status, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != ProcessingJobStatus.RUNNING) {
                return;
            }
            job.setStatus(status);
            job.setFinishedAt(LocalDateTime.now());
            job.setCurrentStep(null);
            job.setErrorMessage(errorMessage);
            if (status == ProcessingJobStatus.SUCCEEDED) {
                job.setProgress(100);
            }
            try {
                jobRepository.saveAndFlush(job);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Cancelled at the last moment; the admin's status stands.
            }
        });
    }

    /** After a restart nothing is running any more: jobs left RUNNING go back to the queue (or fail when out of attempts). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverInterrupted(Collection<ProcessingJobType> types) {
        var stuck = jobRepository.findByStatusAndTypeIn(ProcessingJobStatus.RUNNING, types);
        for (ProcessingJob job : stuck) {
            boolean retry = job.getAttempts() < job.getMaxAttempts();
            job.setStatus(retry ? ProcessingJobStatus.QUEUED : ProcessingJobStatus.FAILED);
            job.setCurrentStep(null);
            if (!retry) {
                job.setFinishedAt(LocalDateTime.now());
                job.setErrorMessage("The server restarted while this job was running, and it has no attempts left");
            }
            jobRepository.save(job);
            log(job.getId(), ProcessingJobLog.Level.WARN, retry ? "Server restarted mid-job — queued again" : "Server restarted mid-job — out of attempts");
        }
        return stuck.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long jobId, ProcessingJobLog.Level level, String message) {
        String text = message.length() > 4000 ? message.substring(0, 4000) + "…" : message;
        logRepository.save(ProcessingJobLog.builder().jobId(jobId).level(level).message(text).build());
    }
}
