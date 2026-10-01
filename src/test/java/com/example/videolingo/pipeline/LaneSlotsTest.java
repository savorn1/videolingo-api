package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneSlotsTest {

    @Test
    void oneAtATimeByDefault() {
        LaneSlots slots = new LaneSlots();
        assertTrue(slots.tryAcquire(1));
        assertFalse(slots.tryAcquire(1));
        slots.release();
        assertTrue(slots.tryAcquire(1));
    }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        LaneSlots slots = new LaneSlots();
        assertTrue(slots.tryAcquire(3));
        assertTrue(slots.tryAcquire(3));
        assertTrue(slots.tryAcquire(3));
        assertFalse(slots.tryAcquire(3));
        assertEquals(3, slots.running());
    }

    @Test
    void releasingNeverGoesBelowZero() {
        LaneSlots slots = new LaneSlots();
        slots.release();
        assertEquals(0, slots.running());
    }

    @Test
    void neverExceedsTheLimitWhenManyThreadsTryAtOnce() throws Exception {
        LaneSlots slots = new LaneSlots();
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            all.add(pool.submit(() -> {
                if (slots.tryAcquire(2)) {
                    granted.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : all) {
            f.get();
        }
        pool.shutdown();
        assertEquals(2, granted.get());
        assertEquals(2, slots.running());
    }
}
