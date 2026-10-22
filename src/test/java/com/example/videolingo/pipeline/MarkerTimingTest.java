package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

// Where a marker's moment lands in a result made from the video (MarkerTiming).
class MarkerTimingTest {

    private final ObjectMapper reader = EditJobParams.reader(new ObjectMapper());

    private LongFunction<Long> timing(String json, Integer segment, Long resultMs) throws Exception {
        return MarkerTiming.forJob(reader.readTree(json), reader, segment, resultMs);
    }

    @Test
    void aTrimMovesMarkersBackByItsStartAndDropsOnesOutsideIt() throws Exception {
        LongFunction<Long> t = timing("{\"operation\":\"TRIM\",\"startMs\":10000,\"endMs\":20000}", null, 10_000L);
        assertEquals(0L, t.apply(10_000));
        assertEquals(5000L, t.apply(15_000));
        assertNull(t.apply(9000));
        assertNull(t.apply(20_000));
    }

    @Test
    void aTrimFollowsTheSpeedThePausesAndTheIntroCard() throws Exception {
        String json = """
                {"operation":"TRIM","startMs":0,"endMs":20000,
                 "speeds":[{"startMs":2000,"endMs":6000,"speed":2.0}],
                 "holds":[{"atMs":8000,"durationMs":1000}],
                 "cards":{"intro":{"text":"Hi","durationMs":3000},"background":"#000000","color":"#ffffff"}}
                """;
        LongFunction<Long> t = timing(json, null, null);
        assertEquals(3000L + 1000, t.apply(1000)); // before the fast part, after the card
        assertEquals(3000L + 2000 + 2000, t.apply(6000)); // 4 s at 2× take 2 s
        assertEquals(3000L + 8000 + 1000, t.apply(10_000)); // ramped to 8 s, after the 1 s pause at (ramped) 6 s
    }

    @Test
    void aBackwardsTrimMirrorsTheMarkers() throws Exception {
        LongFunction<Long> t = timing("{\"operation\":\"TRIM\",\"startMs\":0,\"endMs\":10000,\"reverse\":true}", null, 10_000L);
        assertEquals(7000L, t.apply(3000));
        assertNull(timing("{\"operation\":\"TRIM\",\"startMs\":0,\"reverse\":true}", null, null).apply(3000));
    }

    @Test
    void aCutTakesOutItsRanges() throws Exception {
        LongFunction<Long> t = timing(
                "{\"operation\":\"CUT\",\"cuts\":[{\"startMs\":2000,\"endMs\":4000},{\"startMs\":6000,\"endMs\":7000}]}", null, null);
        assertEquals(1000L, t.apply(1000));
        assertNull(t.apply(3000));
        assertEquals(3000L, t.apply(5000));
        assertEquals(5000L, t.apply(8000));
    }

    @Test
    void aSplitPartKeepsItsOwnRange() throws Exception {
        String json = "{\"operation\":\"SPLIT\",\"segments\":[{\"startMs\":0,\"endMs\":5000},{\"startMs\":5000,\"endMs\":null}]}";
        assertEquals(2000L, timing(json, 1, null).apply(7000));
        assertNull(timing(json, 1, null).apply(4000));
        assertNull(timing(json, 5, null));
    }

    @Test
    void anAudioEditFollowsItsSpeedAndOverlaysChangeNothing() throws Exception {
        assertEquals(5000L, timing("{\"operation\":\"AUDIO\",\"audio\":{\"speed\":2.0}}", null, null).apply(10_000));
        assertEquals(10_000L, timing("{\"operation\":\"OVERLAY\"}", null, null).apply(10_000));
        assertNull(timing("{\"operation\":\"GIF\"}", null, null));
    }

    @Test
    void aTimeMustBeInsideTheResult() {
        assertEquals(500L, MarkerTiming.within(500L, 1000L));
        assertNull(MarkerTiming.within(1000L, 1000L));
        assertNull(MarkerTiming.within(null, 1000L));
        assertEquals(List.of(), List.of());
    }
}
