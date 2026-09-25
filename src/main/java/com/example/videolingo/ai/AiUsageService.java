package com.example.videolingo.ai;

import com.example.videolingo.dto.AiStatusResponse;
import com.example.videolingo.dto.AiUsageDto;
import com.example.videolingo.dto.AiUsageFilterRequest;
import com.example.videolingo.dto.AiUsageSummaryResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.repository.AiUsageRepository;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// AI Usage Tracking + AI Cost Tracking: the per-call log and its roll-ups.
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "costUsd", "latencyMs", "inputTokens", "outputTokens", "model", "feature", "status");
    private static final int MAX_RANGE_DAYS = 366;

    private final AiUsageRepository usageRepository;
    private final AiProperties props;
    private final AiClientService aiClient;
    private final SettingsService settings;

    @Transactional(readOnly = true)
    public AiStatusResponse status() {
        BigDecimal spent = aiClient.monthSpend();
        Map<String, Map<String, BigDecimal>> pricing = new LinkedHashMap<>();
        (props.pricing() == null ? Map.<String, AiProperties.ModelPrice>of() : props.pricing())
                .forEach((m, p) -> pricing.put(m, Map.of("inputPerMtok", p.inputPerMtok(), "outputPerMtok", p.outputPerMtok())));
        Settings.Ai ai = settings.ai();
        boolean exceeded = ai.monthlyBudgetUsd() != null && spent.compareTo(ai.monthlyBudgetUsd()) >= 0;
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (AiFeature f : AiFeature.values()) {
            features.put(f.name(), AiClientService.featureEnabled(ai, f));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("KEY_POINTS", ai.defaultKeyPoints());
        counts.put("QUESTIONS", ai.defaultQuestions());
        counts.put("QUIZ", ai.defaultQuizQuestions());
        return new AiStatusResponse(props.configured(), ai.enabled(), features, counts, ai.maxChatMessageChars(), ai.model(),
                ai.fallbackModel() == null || ai.fallbackModel().isBlank() ? null : ai.fallbackModel(),
                ai.generationEffort(), ai.chatEffort(), spent, ai.monthlyBudgetUsd(), ai.budgetEnforced(), exceeded, pricing);
    }

    @Transactional(readOnly = true)
    public PageResponse<AiUsageDto> list(AiUsageFilterRequest f) {
        List<Specification<AiUsageRecord>> c = new ArrayList<>();
        if (f.getFeature() != null) c.add((r, q, cb) -> cb.equal(r.get("feature"), f.getFeature()));
        if (f.getStatus() != null) c.add((r, q, cb) -> cb.equal(r.get("status"), f.getStatus()));
        if (f.getModel() != null && !f.getModel().isBlank()) c.add((r, q, cb) -> cb.equal(r.get("model"), f.getModel()));
        if (f.getVideoId() != null) c.add((r, q, cb) -> cb.equal(r.get("videoId"), f.getVideoId()));
        if (f.getUsername() != null && !f.getUsername().isBlank()) c.add((r, q, cb) -> cb.equal(r.get("username"), f.getUsername()));
        if (f.getFrom() != null) c.add((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("createdAt"), f.getFrom().atStartOfDay()));
        if (f.getTo() != null) c.add((r, q, cb) -> cb.lessThan(r.get("createdAt"), f.getTo().plusDays(1).atStartOfDay()));
        String sortBy = SORTABLE.contains(f.getSortBy()) ? f.getSortBy() : "createdAt";
        return PageResponse.of(usageRepository.findAll(Specification.allOf(c),
                PageableUtils.of(f.getPage(), Math.min(f.getSize(), 100), sortBy, f.getSortOrder())).map(AiUsageDto::of));
    }

    /** Totals and breakdowns for [from, to] (inclusive dates). Defaults: this calendar month so far. */
    @Transactional(readOnly = true)
    public AiUsageSummaryResponse summary(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            LocalDate t = start;
            start = end;
            end = t;
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            start = end.minusDays(MAX_RANGE_DAYS);
        }
        LocalDateTime a = start.atStartOfDay();
        LocalDateTime b = end.plusDays(1).atStartOfDay();

        AiUsageRepository.Totals t = usageRepository.totals(a, b);
        List<AiUsageSummaryResponse.Group> byModel = groups(usageRepository.byModel(a, b));
        BigDecimal savings = byModel.stream()
                .map(g -> CostCalculator.cacheSavings(props.pricing() == null ? null : props.pricing().get(g.key()),
                        g.cacheWriteTokens(), g.cacheReadTokens(), props.cacheWriteMultiplier(), props.cacheReadMultiplier()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long inputSide = t.getInputTokens() + t.getCacheWriteTokens() + t.getCacheReadTokens();
        double hitRate = inputSide == 0 ? 0 : (double) t.getCacheReadTokens() / inputSide;
        BigDecimal avg = t.getRequests() == 0 ? BigDecimal.ZERO : t.getCost().divide(BigDecimal.valueOf(t.getRequests()), 6, RoundingMode.HALF_UP);

        // Zero-fill so a chart has one bar per day.
        Map<LocalDate, AiUsageRepository.Daily> byDay = usageRepository.daily(a, b).stream()
                .collect(Collectors.toMap(AiUsageRepository.Daily::getDay, d -> d));
        List<AiUsageSummaryResponse.Day> daily = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            AiUsageRepository.Daily row = byDay.get(d);
            daily.add(new AiUsageSummaryResponse.Day(d, row == null ? 0 : row.getRequests(), row == null ? BigDecimal.ZERO : row.getCost()));
        }

        return new AiUsageSummaryResponse(start, end, t.getRequests(), t.getErrors(), t.getRefusals(), t.getInputTokens(), t.getOutputTokens(),
                t.getCacheWriteTokens(), t.getCacheReadTokens(), t.getCost(), avg, t.getAvgLatencyMs(), hitRate, savings, t.getUnpriced(),
                groups(usageRepository.byFeature(a, b)), byModel, groups(usageRepository.byUser(a, b)).stream().limit(10).toList(), daily);
    }

    private static List<AiUsageSummaryResponse.Group> groups(List<AiUsageRepository.Group> rows) {
        return rows.stream().map(g -> new AiUsageSummaryResponse.Group(g.getKey(), g.getRequests(), g.getInputTokens(), g.getOutputTokens(),
                g.getCacheWriteTokens(), g.getCacheReadTokens(), g.getCost())).toList();
    }
}
