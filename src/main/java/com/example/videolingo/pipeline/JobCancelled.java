package com.example.videolingo.pipeline;

// Thrown at a checkpoint once an admin has cancelled the job; the worker
// then stops without touching the (already CANCELLED) status.
public class JobCancelled extends RuntimeException {

    public JobCancelled() {
        super("cancelled", null, false, false);
    }
}
