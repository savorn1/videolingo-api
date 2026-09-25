package com.example.videolingo.learn;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnLogicTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Test
    void goodReviewsGrowTheInterval() {
        Srs.State s = new Srs.State(2.5, 0, 0, 0, NOW);
        s = Srs.review(s, Srs.Grade.GOOD, NOW);
        assertEquals(1, s.intervalDays());
        s = Srs.review(s, Srs.Grade.GOOD, NOW);
        assertEquals(3, s.intervalDays());
        s = Srs.review(s, Srs.Grade.GOOD, NOW);
        assertEquals(8, s.intervalDays());
        assertEquals(NOW.plusDays(8), s.dueAt());
    }

    @Test
    void forgettingResetsAndComesBackSoon() {
        Srs.State s = new Srs.State(2.5, 20, 5, 0, NOW);
        s = Srs.review(s, Srs.Grade.AGAIN, NOW);
        assertEquals(0, s.repetitions());
        assertEquals(1, s.lapses());
        assertEquals(NOW.plusMinutes(10), s.dueAt());
        assertEquals(2.3, s.ease(), 1e-9);
    }

    @Test
    void easeStaysInBounds() {
        Srs.State s = new Srs.State(1.3, 1, 1, 0, NOW);
        assertEquals(1.3, Srs.review(s, Srs.Grade.HARD, NOW).ease(), 1e-9);
        assertTrue(Srs.review(new Srs.State(3.0, 10, 3, 0, NOW), Srs.Grade.EASY, NOW).ease() <= 3.0);
    }

    @Test
    void gradesQuizzes() {
        assertEquals(2, QuizGrader.score(List.of(0, 2, 1), Arrays.asList(0, 2, null)));
        assertEquals(0, QuizGrader.score(List.of(1), List.of()));
        assertEquals(1, QuizGrader.score(List.of(1, 3), List.of(1)));
    }
}
