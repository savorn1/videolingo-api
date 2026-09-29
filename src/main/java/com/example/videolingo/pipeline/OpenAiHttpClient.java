package com.example.videolingo.pipeline;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Sends a request to OpenAI's API and retries it when the failure looks
// transient — a connection problem, a rate limit (429) or a server error
// (5xx). Shared by TextToSpeechClient, Translator and WhisperClient, which
// used to each hand-roll their own attempt loop.
//
// Retried through @Retryable rather than a loop, so the backoff policy lives
// in one place; a 4xx that isn't 429 (bad request, wrong key, …) is returned
// as-is and never retried. Callers check the status code themselves and
// raise a JobFailure with whatever message fits their call.
@Component
class OpenAiHttpClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Retryable(retryFor = TransientApiException.class, maxAttempts = 4, backoff = @Backoff(delay = 1500, multiplier = 2, maxDelay = 8000))
    HttpResponse<byte[]> sendForBytes(HttpRequest request) {
        return send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Retryable(retryFor = TransientApiException.class, maxAttempts = 4, backoff = @Backoff(delay = 1500, multiplier = 2, maxDelay = 8000))
    HttpResponse<String> sendForText(HttpRequest request) {
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> response = http.send(request, handler);
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new TransientApiException(response.statusCode());
            }
            return response;
        } catch (IOException e) {
            throw new TransientApiException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted", e);
        }
    }
}
