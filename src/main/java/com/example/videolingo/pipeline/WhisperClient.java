package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Speech-to-text through OpenAI's transcription API (Whisper), with segment
// timestamps (verbose_json).
//
// The API only accepts a `language` hint for the languages it transcribes
// well (SUPPORTED_HINTS); for any other — Khmer, Lao, Burmese… — it answers
// 400 "Language 'km' is not supported". Those are sent without the hint, so
// the model detects the language itself, and with a short prompt written in
// the language (PRIMING_PROMPTS) that steers it toward the right language
// and script. The language it actually heard comes back in Result.
@Component
@RequiredArgsConstructor
public class WhisperClient {

    public record Segment(long startMs, long endMs, String text) {
    }

    /** `detectedLanguage` is what the model reports hearing, as a lower-case name ("khmer"), or null. */
    public record Result(List<Segment> segments, String detectedLanguage) {
    }

    // ISO 639-1 codes the transcription API accepts as a `language` hint
    // (OpenAI's speech-to-text "supported languages" list).
    static final Set<String> SUPPORTED_HINTS = Set.of(
            "af", "ar", "hy", "az", "be", "bs", "bg", "ca", "zh", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "gl", "de", "el",
            "he", "hi", "hu", "is", "id", "it", "ja", "kn", "kk", "ko", "lv", "lt", "mk", "ms", "mr", "mi", "ne", "no", "fa", "pl",
            "pt", "ro", "ru", "sr", "sk", "sl", "es", "sw", "sv", "tl", "ta", "th", "tr", "uk", "ur", "vi", "cy");

    // For languages without a hint: a line in the language, passed as the
    // `prompt` (text the model treats as coming just before the audio), which
    // makes it far likelier to transcribe in that language and script.
    static final Map<String, String> PRIMING_PROMPTS = Map.of(
            "km", "សូមស្វាគមន៍។ ខាងក្រោមនេះជាការសន្ទនាជាភាសាខ្មែរ។");

    /** The `language` hint to send for this video language, or null when the API wouldn't accept one. */
    static String languageHint(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String code = language.split("[-_]")[0].toLowerCase(Locale.ROOT);
        return SUPPORTED_HINTS.contains(code) ? code : null;
    }

    /** Whether transcription in this language has to rely on the model detecting it. */
    public static boolean needsAutoDetect(String language) {
        return language != null && !language.isBlank() && languageHint(language) == null;
    }

    static String primingPrompt(String language) {
        return language == null ? null : PRIMING_PROMPTS.get(language.split("[-_]")[0].toLowerCase(Locale.ROOT));
    }

    private final PipelineProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public void requireReady() {
        if (!props.speechToTextReady()) {
            throw new JobFailure("Speech-to-text isn't set up — add an OpenAI API key (OPENAI_API_KEY) on the server");
        }
    }

    /** Transcribes one audio file; `language` is the video's language code (may be null). Times are relative to the file. */
    public Result transcribe(Path audio, String language) {
        requireReady();
        String boundary = "----videolingo" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "model", props.whisper());
            field(body, boundary, "response_format", "verbose_json");
            field(body, boundary, "timestamp_granularities[]", "segment");
            String hint = languageHint(language);
            if (hint != null) {
                field(body, boundary, "language", hint);
            } else if (primingPrompt(language) != null) {
                field(body, boundary, "prompt", primingPrompt(language));
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + audio.getFileName()
                    + "\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(Files.readAllBytes(audio));
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the audio: " + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.openaiBase() + "/audio/transcriptions"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + props.openaiApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 401) {
            throw new JobFailure("OpenAI rejected the API key (OPENAI_API_KEY)");
        }
        if (response.statusCode() >= 400) {
            throw new JobFailure("Speech-to-text failed (HTTP " + response.statusCode() + "): " + errorText(response.body()));
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            List<Segment> out = new ArrayList<>();
            for (JsonNode s : root.path("segments")) {
                String text = s.path("text").asText("").strip();
                if (text.isEmpty()) {
                    continue;
                }
                long start = Math.round(s.path("start").asDouble() * 1000);
                long end = Math.max(start + 1, Math.round(s.path("end").asDouble() * 1000));
                out.add(new Segment(start, end, text));
            }
            String detected = root.path("language").asText("");
            return new Result(out, detected.isBlank() ? null : detected.toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            throw new JobFailure("Couldn't read the speech-to-text response", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        // One retry for rate limits / transient server errors.
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if ((r.statusCode() == 429 || r.statusCode() >= 500) && attempt == 0) {
                    Thread.sleep(5000);
                    continue;
                }
                return r;
            } catch (IOException e) {
                if (attempt > 0) {
                    throw new JobFailure("Couldn't reach the speech-to-text service: " + e.getMessage(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobFailure("Interrupted", e);
            }
        }
    }

    private String errorText(String body) {
        try {
            String m = objectMapper.readTree(body).path("error").path("message").asText("");
            return m.isBlank() ? body : m;
        } catch (IOException e) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
