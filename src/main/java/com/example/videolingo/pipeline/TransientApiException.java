package com.example.videolingo.pipeline;

// A call to an external API failed in a way that's worth retrying: a
// connection problem, a rate limit (429) or a server error (5xx). Thrown by
// OpenAiHttpClient so @Retryable can match on it; a 4xx that isn't 429 means
// the request itself was wrong and is never retried.
public class TransientApiException extends RuntimeException {

    // The response's status code, or null when there was no response at all
    // (an IOException reaching the service).
    private final Integer statusCode;

    TransientApiException(int statusCode) {
        super("HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    TransientApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
