package com.example.videolingo.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.example.videolingo.ai.schema.ChaptersOutput;
import com.example.videolingo.ai.schema.QuizOutput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiLogicTest {

    private static final Map<String, AiProperties.ModelPrice> PRICES =
            Map.of("claude-opus-5", new AiProperties.ModelPrice(new BigDecimal("5.00"), new BigDecimal("25.00")));
    private static final BigDecimal W = new BigDecimal("1.25");
    private static final BigDecimal R = new BigDecimal("0.1");

    // ── cost ─────────────────────────────────────────────────────────────

    @Test
    void costAddsTheFourTokenBuckets() {
        // 10k input × $5/M = 0.05; 2k output × $25/M = 0.05; 8k cache write × $6.25/M = 0.05; 100k cache read × $0.50/M
        // = 0.05
        assertEquals(
                new BigDecimal("0.200000"),
                CostCalculator.cost(PRICES, "claude-opus-5", 10_000, 2_000, 8_000, 100_000, W, R));
    }

    @Test
    void unpricedModelHasUnknownCostNotZero() {
        assertNull(CostCalculator.cost(PRICES, "claude-unknown", 1, 1, 0, 0, W, R));
    }

    @Test
    void cacheSavingsNetOffTheWritePremium() {
        AiProperties.ModelPrice p = PRICES.get("claude-opus-5");
        // reads: 100k × 5 × 0.9 / 1M = 0.45; write premium: 10k × 5 × 0.25 / 1M = 0.0125
        assertEquals(new BigDecimal("0.437500"), CostCalculator.cacheSavings(p, 10_000, 100_000, W, R));
        assertTrue(CostCalculator.cacheSavings(p, 10_000, 0, W, R).signum() < 0, "a cache never reused is a loss");
    }

    // ── prompt ───────────────────────────────────────────────────────────

    @Test
    void transcriptLinesCarryTimestampsAndSpeakers() {
        String t = PromptBuilder.formatTranscript(List.of(
                new PromptBuilder.Segment(0, 3000, "いらっしゃいませ！", "Barista"),
                new PromptBuilder.Segment(62_500, 65_000, "Two\nlines", null),
                new PromptBuilder.Segment(3_723_000, 3_725_000, "Late", "")));
        assertEquals("[0:00] Barista: いらっしゃいませ！\n[1:02] Two lines\n[1:02:03] Late\n", t);
    }

    @Test
    void transcriptIsTheCachedSecondSystemBlock() {
        var blocks = PromptBuilder.system(new PromptBuilder.VideoContext("T", "Japanese", "ja", 300, List.of()));
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).cacheControl().isEmpty(), "instructions block is not a breakpoint");
        assertTrue(blocks.get(1).cacheControl().isPresent(), "transcript block is the cache breakpoint");
        assertTrue(blocks.get(1).text().contains("Length: 5:00"));
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void chaptersAreSortedDedupedTrimmedToLengthAndStartAtZero() {
        var in = new ChaptersOutput(List.of(
                new ChaptersOutput.Chapter(90, "Paying", "x"),
                new ChaptersOutput.Chapter(5, "Greeting", "x"),
                new ChaptersOutput.Chapter(90, "Duplicate", "x"),
                new ChaptersOutput.Chapter(9999, "Past the end", "x")));
        var out = OutputValidator.chapters(in, 300_000);
        assertEquals(
                List.of(0, 90),
                out.value().chapters().stream()
                        .map(ChaptersOutput.Chapter::startSeconds)
                        .toList());
        // duplicate dropped, past-the-end dropped, first moved to 0:00
        assertEquals(3, out.warnings().size());
    }

    @Test
    void quizDropsQuestionsWhoseAnswerCannotBeRight() {
        var in = new QuizOutput(List.of(
                new QuizOutput.QuizQuestion("Good?", List.of("a", "b", "c"), 1, "because", 10),
                new QuizOutput.QuizQuestion("Index out of range?", List.of("a", "b"), 2, "", 10),
                new QuizOutput.QuizQuestion("Duplicate options?", List.of("Yes", "yes", "no"), 0, "", 10),
                new QuizOutput.QuizQuestion("One option?", List.of("a"), 0, "", 10)));
        var out = OutputValidator.quiz(in, 60_000);
        assertEquals(1, out.value().questions().size());
        assertEquals(3, out.warnings().size());
    }
}
