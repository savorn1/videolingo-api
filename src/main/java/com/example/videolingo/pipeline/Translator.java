package com.example.videolingo.pipeline;

import com.example.videolingo.ai.schema.TranslationOutput;
import com.example.videolingo.glossary.GlossaryEntry;
import com.example.videolingo.glossary.GlossaryPrompt;
import com.example.videolingo.glossary.GlossaryService;
import com.example.videolingo.repository.LanguageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Translates transcript lines with OpenAI, line for line, so every
// translated line keeps its original timing. Lines go in batches; each batch
// sees the previous few lines for context. Enabled glossaries for the
// language pair are followed: each batch's prompt lists the terms it uses.
@Component
@RequiredArgsConstructor
public class Translator {

    private static final int BATCH = 80;
    private static final int CONTEXT_LINES = 5;
    private static final long MAX_TOKENS = 16_000;

    // The reply must be {"lines": [{"index": n, "text": "..."}]} — see TranslationOutput.
    private static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                    "name", "translation",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "additionalProperties", false,
                            "required", List.of("lines"),
                            "properties", Map.of("lines", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "additionalProperties", false,
                                            "required", List.of("index", "text"),
                                            "properties", Map.of(
                                                    "index", Map.of("type", "integer"),
                                                    "text", Map.of("type", "string"))))))));

    private final PipelineProperties props;
    private final ObjectMapper objectMapper;
    private final LanguageRepository languageRepository;
    private final GlossaryService glossaryService;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public void requireReady() {
        if (!props.translationReady()) {
            throw new JobFailure("Translation isn't set up — add an OpenAI API key (OPENAI_API_KEY) on the server");
        }
    }

    /** Same size and order as `lines`. `forSpeech` asks for wording short enough to be spoken in the original time. */
    public List<String> translate(List<String> lines, String from, String to, boolean forSpeech, long videoId, Long transcriptId, JobContext ctx) {
        requireReady();
        String fromName = name(from);
        String toName = name(to);
        String system = "You translate video subtitles from " + fromName + " (" + from + ") into " + toName + " (" + to + ").\n"
                        + "- Translate every line; return exactly one line per input index.\n"
                        + "- Keep each line's meaning within that line — don't move text between lines.\n"
                        + "- Use natural, everyday spoken " + toName + ", not word-for-word translation.\n"
                        + "- Keep names, numbers and technical terms accurate.\n"
                        + (forSpeech
                        ? "- The lines will be read aloud by a voice over the original timing, so keep each one about as short as the original.\n"
                        : "")
                        + "- Output only the translation, no notes.\n";
        List<GlossaryEntry> glossary = glossaryService.termsFor(from, to);
        if (!glossary.isEmpty()) {
            ctx.info("Following " + glossary.size() + " glossary term(s) for " + fromName + " → " + toName);
        }

        List<String> out = new ArrayList<>(lines);
        int batches = (lines.size() + BATCH - 1) / BATCH;
        int missing = 0;
        for (int b = 0; b < batches; b++) {
            int start = b * BATCH;
            int end = Math.min(lines.size(), start + BATCH);
            ctx.progress(b * 100 / Math.max(1, batches), "Translating to " + toName + " (" + (b + 1) + " of " + batches + ")");

            StringBuilder prompt = new StringBuilder();
            if (start > 0) {
                prompt.append("Earlier lines, for context only (don't translate):\n");
                for (int i = Math.max(0, start - CONTEXT_LINES); i < start; i++) {
                    prompt.append(lines.get(i)).append('\n');
                }
                prompt.append('\n');
            }
            prompt.append("Translate these lines:\n");
            for (int i = start; i < end; i++) {
                prompt.append('[').append(i).append("] ").append(lines.get(i).replace('\n', ' ')).append('\n');
            }

            String rules = GlossaryPrompt.section(GlossaryPrompt.relevant(glossary, lines.subList(start, end)));
            TranslationOutput result = complete(system + rules, prompt.toString());
            Map<Integer, String> byIndex = new HashMap<>();
            for (TranslationOutput.Line line : result.lines()) {
                if (line.text() != null && !line.text().isBlank()) {
                    byIndex.put(line.index(), line.text().strip());
                }
            }
            for (int i = start; i < end; i++) {
                String t = byIndex.get(i);
                if (t == null) {
                    missing++;
                } else {
                    out.set(i, t);
                }
            }
        }
        if (missing > 0) {
            ctx.warn(missing + " line(s) came back untranslated and were kept in " + fromName);
        }
        return out;
    }

    private TranslationOutput complete(String system, String prompt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", props.translationModel());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt)));
        payload.put("response_format", RESPONSE_FORMAT);
        payload.put("max_completion_tokens", MAX_TOKENS);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(props.openaiBase() + "/chat/completions"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + props.openaiApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    JsonNode message = objectMapper.readTree(r.body()).path("choices").path(0).path("message");
                    if (message.hasNonNull("refusal")) {
                        throw new JobFailure("Translation was refused: " + message.path("refusal").asText());
                    }
                    return objectMapper.readValue(message.path("content").asText(), TranslationOutput.class);
                }
                if (r.statusCode() == 401) {
                    throw new JobFailure("OpenAI rejected the API key (OPENAI_API_KEY)");
                }
                if (r.statusCode() != 429 && r.statusCode() < 500) {
                    throw new JobFailure("Translation failed (HTTP " + r.statusCode() + "): " + errorText(r.body()));
                }
            } catch (IOException e) {
                if (attempt == 3) {
                    throw new JobFailure("Couldn't reach OpenAI for translation: " + e.getMessage(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobFailure("Interrupted", e);
            }
            try {
                Thread.sleep(2000L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobFailure("Interrupted", e);
            }
        }
        throw new JobFailure("OpenAI kept rate-limiting the translation requests — try again later");
    }

    private String errorText(String body) {
        try {
            return objectMapper.readTree(body).path("error").path("message").asText(body);
        } catch (IOException e) {
            return body;
        }
    }

    public String name(String code) {
        return languageRepository.findByCodeIgnoreCase(code).map(l -> l.getName()).orElse(code);
    }
}
