package com.example.videolingo.pipeline;

import java.util.concurrent.atomic.AtomicInteger;

// Counts the jobs running in one lane, so the worker never runs more than the lane allows — even when
// the scheduled poll and a job that has just finished both look for work at the same moment.
final class LaneSlots {

    private final AtomicInteger running = new AtomicInteger();

    /** Takes a place if fewer than `max` are taken; false when the lane is full. */
    boolean tryAcquire(int max) {
        while (true) {
            int current = running.get();
            if (current >= max) {
                return false;
            }
            if (running.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Gives a place back (a job finished, or none was found to run). */
    void release() {
        running.updateAndGet(n -> Math.max(0, n - 1));
    }

    int running() {
        return running.get();
    }
}
