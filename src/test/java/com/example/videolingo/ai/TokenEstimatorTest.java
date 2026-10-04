package com.example.videolingo.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void countsAboutFourAsciiCharactersPerToken() {
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(1, TokenEstimator.estimate("abcd"));
        assertEquals(3, TokenEstimator.estimate("Hello, world"));
    }

    @Test
    void countsOtherScriptsPerCharacter() {
        assertEquals(4, TokenEstimator.estimate("東京タワー".substring(0, 4)));
        assertEquals(1 + 2, TokenEstimator.estimate("ab 日本"));
    }
}
