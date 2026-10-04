package com.example.videolingo.ai;

// A rough token count for text we haven't sent yet, for cost estimates.
// Claude averages about 4 characters per token for English and other
// ASCII-heavy text, but close to one token per character for scripts like
// Chinese, Japanese, Khmer or Thai — so the two are counted separately.
// Deliberately errs high rather than low. Pure and static so it's unit-tested.
public final class TokenEstimator {

    private TokenEstimator() {}

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp < 128) {
                ascii++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return (ascii + 3) / 4 + other;
    }
}
