package com.example.videolingo.learn;

import com.example.videolingo.entity.StudyCard;
import java.time.LocalDateTime;

// Spaced repetition, a small take on SM-2: each review's grade moves the
// card's ease and interval, and sets when it's due next. Pure and static so
// it's unit-tested.
public final class Srs {

    public enum Grade {
        AGAIN,
        HARD,
        GOOD,
        EASY
    }

    public record State(double ease, int intervalDays, int repetitions, int lapses, LocalDateTime dueAt) {}

    static final double MIN_EASE = 1.3;
    static final double MAX_EASE = 3.0;
    /** A forgotten card comes back within the same study session. */
    static final int AGAIN_MINUTES = 10;

    static final int MAX_INTERVAL_DAYS = 365;

    private Srs() {}

    public static State review(State s, Grade grade, LocalDateTime now) {
        double ease = s.ease();
        int interval;
        int reps = s.repetitions();
        int lapses = s.lapses();
        switch (grade) {
            case AGAIN -> {
                ease -= 0.2;
                reps = 0;
                lapses++;
                interval = 0;
            }
            case HARD -> {
                ease -= 0.15;
                reps++;
                interval = reps == 1 ? 1 : Math.max(s.intervalDays() + 1, (int) Math.round(s.intervalDays() * 1.2));
            }
            case GOOD -> {
                reps++;
                interval = reps == 1
                        ? 1
                        : reps == 2 ? 3 : Math.max(s.intervalDays() + 1, (int) Math.round(s.intervalDays() * ease));
            }
            case EASY -> {
                ease += 0.15;
                reps++;
                interval =
                        reps == 1 ? 3 : Math.max(s.intervalDays() + 2, (int) Math.round(s.intervalDays() * ease * 1.3));
            }
            default -> throw new IllegalArgumentException("grade");
        }
        ease = Math.max(MIN_EASE, Math.min(MAX_EASE, ease));
        interval = Math.min(interval, MAX_INTERVAL_DAYS);
        LocalDateTime due = interval == 0 ? now.plusMinutes(AGAIN_MINUTES) : now.plusDays(interval);
        return new State(ease, interval, reps, lapses, due);
    }

    public static State of(StudyCard c) {
        return new State(c.getEase(), c.getIntervalDays(), c.getRepetitions(), c.getLapses(), c.getDueAt());
    }
}
