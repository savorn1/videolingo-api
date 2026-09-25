package com.example.videolingo.learn;

import java.util.List;

// Scores quiz answers. Pure and static so it's unit-tested.
public final class QuizGrader {

    private QuizGrader() {
    }

    /** How many answers match; a missing or out-of-range answer counts as wrong. */
    public static int score(List<Integer> correct, List<Integer> answers) {
        int score = 0;
        for (int i = 0; i < correct.size(); i++) {
            Integer a = answers != null && i < answers.size() ? answers.get(i) : null;
            if (a != null && a.equals(correct.get(i))) {
                score++;
            }
        }
        return score;
    }
}
