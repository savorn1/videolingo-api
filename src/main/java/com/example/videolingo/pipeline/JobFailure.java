package com.example.videolingo.pipeline;

// A job can't go on, for a reason worth showing the admin as-is (missing
// tool or key, no speech found, …). Anything else is reported generically.
public class JobFailure extends RuntimeException {

    public JobFailure(String message) {
        super(message);
    }

    public JobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
