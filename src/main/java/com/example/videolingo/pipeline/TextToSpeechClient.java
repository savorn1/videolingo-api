package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Text-to-speech through OpenAI's speech API. Asks for raw 24 kHz 16-bit mono
// PCM and wraps it in a WAV header, which WavMixer places on the dub timeline.
@Component
@RequiredArgsConstructor
public class TextToSpeechClient {

    public record Voice(String id, String name, String gender) {
    }

    private static final int SAMPLE_RATE = 24_000;

    // OpenAI voices aren't tied to a language — each one speaks whatever text
    // it's given — so every supported language offers the same set.
    private static final List<Voice> OPENAI_VOICES = List.of(
            new Voice("nova", "Nova", "Female"),
            new Voice("shimmer", "Shimmer", "Female"),
            new Voice("coral", "Coral", "Female"),
            new Voice("alloy", "Alloy", "Neutral"),
            new Voice("echo", "Echo", "Male"),
            new Voice("onyx", "Onyx", "Male"),
            new Voice("ash", "Ash", "Male"));

    // Languages a dub can be made in. Khmer (km) is the main one; a few
    // common languages are included so any translation can be voiced.
    public static final Map<String, List<Voice>> VOICES = voices();

    private static Map<String, List<Voice>> voices() {
        Map<String, List<Voice>> m = new LinkedHashMap<>();
        for (String lang : List.of("km", "en", "th", "vi", "zh", "ja", "ko", "fr", "es")) {
            m.put(lang, OPENAI_VOICES);
        }
        return m;
    }

    public static List<Voice> voicesFor(String language) {
        return VOICES.getOrDefault(language == null ? "" : language.split("[-_]")[0].toLowerCase(), List.of());
    }

    private final PipelineProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public void requireReady() {
        if (!props.textToSpeechReady()) {
            throw new JobFailure("Text-to-speech isn't set up — add an OpenAI API key (OPENAI_API_KEY) on the server");
        }
    }

    /** Speaks `text` with `voiceId`; `rate` 1.0 = normal, 1.3 = 30 % faster. */
    public byte[] synthesize(String text, String voiceId, double rate) {
        requireReady();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.ttsModel());
        payload.put("voice", voiceId);
        payload.put("input", text);
        payload.put("response_format", "pcm");
        payload.put("speed", Math.max(0.25, Math.min(4.0, rate)));
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.openaiBase() + "/audio/speech"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + props.openaiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) {
                    return wav(r.body());
                }
                if (r.statusCode() == 401) {
                    throw new JobFailure("OpenAI rejected the API key (OPENAI_API_KEY)");
                }
                if (r.statusCode() != 429 && r.statusCode() < 500) {
                    throw new JobFailure("Text-to-speech failed (HTTP " + r.statusCode() + ") for voice " + voiceId);
                }
            } catch (IOException e) {
                if (attempt == 3) {
                    throw new JobFailure("Couldn't reach OpenAI text-to-speech: " + e.getMessage(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobFailure("Interrupted", e);
            }
            sleep(1500L * (attempt + 1));
        }
        throw new JobFailure("OpenAI kept rate-limiting the text-to-speech requests — try again later");
    }

    // Raw 16-bit little-endian mono PCM → a minimal WAV file.
    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobFailure("Interrupted", e);
        }
    }
}
