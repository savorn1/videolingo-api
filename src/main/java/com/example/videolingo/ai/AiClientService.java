package com.example.videolingo.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.entity.AiUsageStatus;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiUsageRepository;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// Every Claude call goes through here, so each one — success, refusal,
// truncation or error — leaves exactly one ai_usage row with its tokens and
// cost. A refusal is retried once on the fallback model (its own usage row,
// billed at its own price).
@Service
@RequiredArgsConstructor
@Slf4j
public class AiClientService {

    private final ObjectProvider<AnthropicClient> clientProvider;
    private final AiProperties props;
    private final SettingsService settings;
    private final AiUsageRepository usageRepository;

    /** Who and what a call is for — copied onto its usage row. */
    public record Call(AiFeature feature, Long videoId, Long transcriptId, Long chatId, String username) {
    }

    public record Result<T>(T value, AiUsageRecord usage) {
    }

    // What one attempt produced: the raw message (usage, stop reason) and a
    // deferred read of the value (parsing can fail and must be recorded).
    private record Raw<T>(Message message, Supplier<T> value) {
    }

    /** Structured output: the response is parsed into {@code type}, whose JSON schema is sent with the request. */
    public <T> Result<T> structured(Call call, List<TextBlockParam> system, String userPrompt, Class<T> type, long maxTokens) {
        return structured(call, system, userPrompt, type, maxTokens, settings.ai().generationEffort());
    }

    /** Same, at a given effort — "low" for quick, simple answers like word lookups. */
    public <T> Result<T> structured(Call call, List<TextBlockParam> system, String userPrompt, Class<T> type, long maxTokens, String effortLevel) {
        return withFallback(call, model -> {
            StructuredMessageCreateParams<T> schemaOnly = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .systemOfTextBlockParams(system)
                    .outputConfig(type)
                    .addUserMessage(userPrompt)
                    .build();
            // .outputConfig(type) replaces the whole output_config, so effort is
            // added afterwards onto the derived one (format + effort together).
            MessageCreateParams raw = schemaOnly.rawParams();
            OutputConfig withEffort = raw.outputConfig().orElseThrow().toBuilder()
                    .effort(effort(effortLevel))
                    .build();
            StructuredMessageCreateParams<T> params = new StructuredMessageCreateParams<>(type,
                    raw.toBuilder().outputConfig(withEffort).build());
            StructuredMessage<T> response = client().messages().create(params);
            return new Raw<>(response.rawMessage(), () -> response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("the response had no text block")));
        });
    }

    /** Plain-text chat turn. {@code history} is the conversation so far (alternating user/assistant). */
    public Result<String> chat(Call call, List<TextBlockParam> system, List<MessageParam> history, String userMessage) {
        return withFallback(call, model -> {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4096)
                    .systemOfTextBlockParams(system)
                    .outputConfig(OutputConfig.builder().effort(effort(settings.ai().chatEffort())).build())
                    .messages(history)
                    .addUserMessage(userMessage)
                    // Auto-caches the conversation up to the newest turn, so each
                    // turn re-reads the previous ones from cache.
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build();
            Message response = client().messages().create(params);
            return new Raw<>(response, () -> response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .collect(Collectors.joining()));
        });
    }

    // ── internals ─────────────────────────────────────────────────────────

    private <T> Result<T> withFallback(Call call, Function<String, Raw<T>> request) {
        requireReady(call.feature());
        Settings.Ai ai = settings.ai();
        Attempt<T> first = attempt(call, ai.model(), null, request);
        if (!first.refused()) {
            return first.result();
        }
        if (ai.fallbackModel() != null && !ai.fallbackModel().isBlank()) {
            log.info("{} declined the {} request; retrying on {}", ai.model(), call.feature(), ai.fallbackModel());
            Attempt<T> second = attempt(call, ai.fallbackModel(), ai.model(), request);
            if (!second.refused()) {
                return second.result();
            }
            throw refusedError(second.reason());
        }
        throw refusedError(first.reason());
    }

    private record Attempt<T>(Result<T> result, boolean refused, String reason) {
    }

    private <T> Attempt<T> attempt(Call call, String model, String fallbackFrom, Function<String, Raw<T>> request) {
        long started = System.nanoTime();
        AiUsageRecord row = AiUsageRecord.builder()
                .feature(call.feature()).videoId(call.videoId()).transcriptId(call.transcriptId()).chatId(call.chatId())
                .username(call.username()).model(model).fallbackFrom(fallbackFrom).build();
        Raw<T> raw;
        try {
            raw = request.apply(model);
        } catch (AnthropicServiceException e) {
            row.setStatus(AiUsageStatus.ERROR);
            row.setLatencyMs(elapsedMs(started));
            row.setErrorMessage(truncate("HTTP " + e.statusCode() + ": " + e.getMessage()));
            usageRepository.save(row);
            throw mapServiceError(e);
        } catch (AnthropicIoException e) {
            row.setStatus(AiUsageStatus.ERROR);
            row.setLatencyMs(elapsedMs(started));
            row.setErrorMessage(truncate("Network error: " + e.getMessage()
                    + (e.getCause() != null ? " (" + e.getCause() + ")" : "")));
            usageRepository.save(row);
            log.warn("AI request for {} failed at the network level", call.feature(), e);
            throw new AppException(HttpStatus.GATEWAY_TIMEOUT, "Couldn't reach the AI service — try again in a moment");
        }

        Message message = raw.message();
        applyUsage(row, message);
        row.setLatencyMs(elapsedMs(started));
        StopReason stop = message.stopReason().orElse(null);
        row.setStopReason(stop == null ? null : stop.toString());

        if (StopReason.REFUSAL.equals(stop)) {
            String reason = message.stopDetails()
                    .map(d -> d.category().map(Object::toString).orElse("unspecified")
                            + d.explanation().map(x -> " — " + x).orElse(""))
                    .orElse("unspecified");
            row.setStatus(AiUsageStatus.REFUSED);
            row.setErrorMessage(truncate("Declined: " + reason));
            usageRepository.save(row);
            return new Attempt<>(null, true, reason);
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            row.setStatus(AiUsageStatus.TRUNCATED);
            row.setErrorMessage("Output hit max_tokens before finishing");
            usageRepository.save(row);
            throw new AppException(HttpStatus.BAD_GATEWAY, call.feature() == AiFeature.CHAT
                    ? "The answer was too long and got cut off — try a narrower question"
                    : "The AI response was cut off before it finished — try asking for fewer items");
        }
        T value;
        try {
            value = raw.value().get();
        } catch (RuntimeException e) {
            row.setStatus(AiUsageStatus.ERROR);
            row.setErrorMessage(truncate("Unreadable response: " + e.getMessage()));
            usageRepository.save(row);
            throw new AppException(HttpStatus.BAD_GATEWAY, "The AI response couldn't be read — try again");
        }
        row.setStatus(AiUsageStatus.SUCCESS);
        usageRepository.save(row);
        return new Attempt<>(new Result<>(value, row), false, null);
    }

    private void applyUsage(AiUsageRecord row, Message message) {
        Usage u = message.usage();
        // Bill the model that actually answered.
        String served = message.model().asString();
        row.setModel(served);
        row.setRequestId(message.id());
        row.setInputTokens(u.inputTokens());
        row.setOutputTokens(u.outputTokens());
        row.setCacheWriteTokens(u.cacheCreationInputTokens().orElse(0L));
        row.setCacheReadTokens(u.cacheReadInputTokens().orElse(0L));
        row.setCostUsd(CostCalculator.cost(props.pricing(), served, row.getInputTokens(), row.getOutputTokens(),
                row.getCacheWriteTokens(), row.getCacheReadTokens(), props.cacheWriteMultiplier(), props.cacheReadMultiplier()));
    }

    private AnthropicClient client() {
        AnthropicClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI isn't configured — set ANTHROPIC_API_KEY on the server");
        }
        return client;
    }

    // Checked before any call: configured, switched on (overall and for this
    // feature), and under the monthly budget if one is enforced.
    public void requireReady(AiFeature feature) {
        client();
        Settings.Ai ai = settings.ai();
        if (!ai.enabled()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI features are turned off in Settings");
        }
        if (!featureEnabled(ai, feature)) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, AiFeatureNames.label(feature) + " is turned off in Settings");
        }
        if (ai.budgetEnforced() && ai.monthlyBudgetUsd() != null) {
            BigDecimal spent = monthSpend();
            if (spent.compareTo(ai.monthlyBudgetUsd()) >= 0) {
                throw new AppException(HttpStatus.PAYMENT_REQUIRED, "This month's AI budget of $" + ai.monthlyBudgetUsd().toPlainString()
                        + " has been used ($" + spent.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " spent)");
            }
        }
    }

    public static boolean featureEnabled(Settings.Ai ai, AiFeature feature) {
        return switch (feature) {
            case SUMMARY -> ai.summaryEnabled();
            case CHAPTERS -> ai.chaptersEnabled();
            case KEY_POINTS -> ai.keyPointsEnabled();
            case QUESTIONS -> ai.questionsEnabled();
            case QUIZ -> ai.quizEnabled();
            case CHAT -> ai.chatEnabled();
            // Part of transcript/dubbing jobs; governed by the overall AI switch and budget.
            case TRANSLATION -> true;
            // Learners' word lookups: tiny and cached, so only the overall switch and budget apply.
            case LOOKUP -> true;
        };
    }

    public BigDecimal monthSpend() {
        LocalDateTime from = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return usageRepository.sumCost(from, from.plusMonths(1));
    }

    private static AppException mapServiceError(AnthropicServiceException e) {
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return new AppException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service rejected the server's API key — check ANTHROPIC_API_KEY");
        }
        if (e instanceof RateLimitException) {
            return new AppException(HttpStatus.TOO_MANY_REQUESTS, "The AI service is busy (rate limited) — try again in a minute");
        }
        if (e.statusCode() >= 500) {
            return new AppException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service is temporarily unavailable — try again shortly");
        }
        return new AppException(HttpStatus.BAD_GATEWAY, "The AI service rejected the request: " + e.getMessage());
    }

    private static AppException refusedError(String reason) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "The AI declined to answer this request (" + reason + ")");
    }

    private static OutputConfig.Effort effort(String value) {
        return switch (value == null ? "high" : value.toLowerCase()) {
            case "low" -> OutputConfig.Effort.LOW;
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.HIGH;
        };
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "…";
    }
}
