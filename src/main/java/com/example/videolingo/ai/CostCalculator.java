package com.example.videolingo.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

// USD cost of one Claude call from its token usage and the configured price table.
// Pure and static so the arithmetic is unit-tested.
public final class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private CostCalculator() {
    }

    /**
     * input + output at list price; cache writes at input × writeMultiplier and
     * cache reads at input × readMultiplier (Claude reports those three input
     * buckets separately — they don't overlap). Null when the model has no
     * price configured, so an unpriced model shows "unknown" rather than $0.
     */
    public static BigDecimal cost(Map<String, AiProperties.ModelPrice> pricing, String model,
                                  long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens,
                                  BigDecimal writeMultiplier, BigDecimal readMultiplier) {
        AiProperties.ModelPrice price = pricing == null ? null : pricing.get(model);
        if (price == null) {
            return null;
        }
        BigDecimal in = price.inputPerMtok();
        BigDecimal total = in.multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputPerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .add(in.multiply(writeMultiplier).multiply(BigDecimal.valueOf(cacheWriteTokens)))
                .add(in.multiply(readMultiplier).multiply(BigDecimal.valueOf(cacheReadTokens)));
        return total.divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /**
     * What caching saved: cache reads priced at full input rate minus what they
     * actually cost, less the write premium paid to create the cache. Negative
     * when a cache was written but never reused.
     */
    public static BigDecimal cacheSavings(AiProperties.ModelPrice price, long cacheWriteTokens, long cacheReadTokens,
                                          BigDecimal writeMultiplier, BigDecimal readMultiplier) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = price.inputPerMtok();
        BigDecimal saved = in.multiply(BigDecimal.ONE.subtract(readMultiplier)).multiply(BigDecimal.valueOf(cacheReadTokens));
        BigDecimal premium = in.multiply(writeMultiplier.subtract(BigDecimal.ONE)).multiply(BigDecimal.valueOf(cacheWriteTokens));
        return saved.subtract(premium).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
