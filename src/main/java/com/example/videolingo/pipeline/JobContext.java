package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJobLog;
import java.nio.file.Path;

// What a running job step gets: where to put temporary files, and how to
// report progress. Progress is given within the step's own 0–100 and mapped
// onto the slice of the job it was given (see slice), so steps compose:
// dubbing runs transcription in 0–30 %, translation in 30–45 %, and so on.
public final class JobContext {

    private final JobStore store;
    private final long jobId;
    private final Path workDir;
    private final int from;
    private final int to;

    JobContext(JobStore store, long jobId, Path workDir) {
        this(store, jobId, workDir, 0, 100);
    }

    private JobContext(JobStore store, long jobId, Path workDir, int from, int to) {
        this.store = store;
        this.jobId = jobId;
        this.workDir = workDir;
        this.from = from;
        this.to = to;
    }

    public long jobId() {
        return jobId;
    }

    public Path workDir() {
        return workDir;
    }

    /** A context whose 0–100 covers only [startPct, endPct] of this one. */
    public JobContext slice(int startPct, int endPct) {
        int a = map(startPct);
        int b = map(endPct);
        return new JobContext(store, jobId, workDir, a, b);
    }

    /** Reports progress (0–100 within this context) and the current step. Also a cancellation checkpoint. */
    public void progress(int pct, String step) {
        int overall = map(Math.max(0, Math.min(100, pct)));
        String text = step == null ? null : (step.length() > 200 ? step.substring(0, 199) + "…" : step);
        store.update(jobId, job -> {
            job.setProgress(Math.max(job.getProgress(), overall));
            if (text != null) {
                job.setCurrentStep(text);
            }
        });
    }

    /** Cancellation checkpoint without changing anything visible. */
    public void checkpoint() {
        store.update(jobId, job -> {});
    }

    public void info(String message) {
        store.log(jobId, ProcessingJobLog.Level.INFO, message);
    }

    public void warn(String message) {
        store.log(jobId, ProcessingJobLog.Level.WARN, message);
    }

    private int map(int pct) {
        return from + Math.round((to - from) * (pct / 100f));
    }
}
