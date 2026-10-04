package com.example.videolingo.ai;

import com.anthropic.models.messages.TextBlockParam;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiUsageRepository;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// "About how much will this cost?" before running an AI generation.
//   input:  measured from the last successful call on the same transcript
//           when there is one, else estimated from the prompt text;
//   output: the average of recent successful calls of the same feature,
//           else a typical size;
//   price:  the Settings › AI model's list price, pricing the transcript
//           block as a cache write (worst case: the cache has expired).
@Service
@RequiredArgsConstructor
public class AiEstimateService {

    private static final int HISTORY = 20;
    // Typical outputs (incl. thinking) when there's no history yet.
    private static final Map<AiFeature, Long> DEFAULT_OUTPUT = Map.of(
            AiFeature.SUMMARY,
            2_500L,
            AiFeature.CHAPTERS,
            2_000L,
            AiFeature.KEY_POINTS,
            2_000L,
            AiFeature.QUESTIONS,
            2_500L,
            AiFeature.QUIZ,
            4_000L);

    public record Estimate(
            AiFeature type,
            String model,
            long inputTokens,
            long outputTokens,
            boolean inputMeasured,
            int outputSamples,
            BigDecimal costUsd,
            BigDecimal monthSpendUsd,
            BigDecimal monthlyBudgetUsd,
            boolean budgetEnforced,
            boolean wouldExceedBudget) {}

    private final AiContextService contextService;
    private final AiUsageRepository usageRepository;
    private final AiClientService aiClient;
    private final AiProperties props;
    private final SettingsService settings;

    @Transactional(readOnly = true)
    public Estimate estimate(Long videoId, AiFeature type, Long transcriptId, Integer count) {
        if (type == null || !DEFAULT_OUTPUT.containsKey(type)) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "Estimates are available for SUMMARY, CHAPTERS, KEY_POINTS, QUESTIONS and QUIZ");
        }
        AiContextService.Loaded loaded = contextService.load(videoId, transcriptId);
        Settings.Ai ai = settings.ai();

        // Input: cached transcript block + uncached instructions/prompt.
        List<TextBlockParam> system = PromptBuilder.system(loaded.context());
        long base = TokenEstimator.estimate(system.get(0).text());
        long transcriptBlock = TokenEstimator.estimate(system.get(1).text());
        long prompt = TokenEstimator.estimate(PromptBuilder.task(
                PromptBuilder.AiTask.valueOf(type.name()), "English (en)", count == null ? 8 : count));
        boolean measured = false;
        List<AiUsageRecord> previous =
                usageRepository.latestOnTranscript(loaded.transcript().getId(), PageRequest.of(0, 1));
        if (!previous.isEmpty()) {
            AiUsageRecord p = previous.get(0);
            long total = p.getInputTokens() + p.getCacheWriteTokens() + p.getCacheReadTokens();
            if (total > 0) {
                transcriptBlock = Math.max(0, total - base - prompt);
                measured = true;
            }
        }
        long uncached = base + prompt;

        List<Long> outputs = usageRepository.recentOutputTokens(type, PageRequest.of(0, HISTORY));
        long output = outputs.isEmpty()
                ? DEFAULT_OUTPUT.get(type)
                : Math.round(
                        outputs.stream().mapToLong(Long::longValue).average().orElse(0));

        BigDecimal cost = CostCalculator.cost(
                props.pricing(),
                ai.model(),
                uncached,
                output,
                transcriptBlock,
                0,
                props.cacheWriteMultiplier(),
                props.cacheReadMultiplier());
        BigDecimal spent = aiClient.monthSpend();
        boolean exceeds =
                cost != null && ai.monthlyBudgetUsd() != null && spent.add(cost).compareTo(ai.monthlyBudgetUsd()) > 0;
        return new Estimate(
                type,
                ai.model(),
                uncached + transcriptBlock,
                output,
                measured,
                outputs.size(),
                cost,
                spent,
                ai.monthlyBudgetUsd(),
                ai.budgetEnforced(),
                exceeds);
    }
}
