package com.example.videolingo.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.videolingo.streaming.SeriesReadiness.Input;
import com.example.videolingo.streaming.SeriesReadiness.Level;
import com.example.videolingo.streaming.SeriesReadiness.Problem;
import com.example.videolingo.streaming.SeriesReadiness.State;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeriesReadinessTest {

    private static Input ready(List<Boolean> subtitles) {
        return new Input("PUBLIC", "ROMANCE", "c", "b", "v", false, 3, null, subtitles, 0, 0);
    }

    @Test
    void complete_public_series_is_live_with_nothing_to_fix() {
        var r = SeriesReadiness.check(ready(List.of(true, true)));
        assertThat(r.state()).isEqualTo(State.LIVE);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void private_collection_is_not_public_even_with_episodes() {
        var in = new Input("PRIVATE", "ROMANCE", "c", "b", "v", false, 3, null, List.of(true), 0, 0);
        var r = SeriesReadiness.check(in);
        assertThat(r.state()).isEqualTo(State.NOT_PUBLIC);
        assertThat(r.problems()).extracting(Problem::level).containsExactly(Level.ERROR);
        assertThat(r.problems().get(0).message()).contains("Private");
    }

    @Test
    void public_collection_without_released_videos_mentions_scheduled_ones() {
        var in = new Input("PUBLIC", "ROMANCE", "c", "b", "v", false, 3, null, List.of(), 1, 2);
        var r = SeriesReadiness.check(in);
        assertThat(r.state()).isEqualTo(State.NO_EPISODES);
        assertThat(r.problems())
                .extracting(Problem::message)
                .contains(
                        "No released episodes yet (2 scheduled)",
                        "1 video is skipped: disabled, not Public, archived or in the trash");
    }

    @Test
    void missing_subtitles_are_listed_as_ranges() {
        var r = SeriesReadiness.check(ready(List.of(false, false, false, true, false, true, false, false)));
        assertThat(r.problems())
                .extracting(Problem::message)
                .containsExactly("Episodes 1–3, 5, 7–8 have no published Khmer or default subtitles");
    }

    @Test
    void missing_artwork_and_category_are_warnings() {
        var in = new Input("PUBLIC", null, null, null, null, false, 3, 1, List.of(true), 0, 0);
        var r = SeriesReadiness.check(in);
        assertThat(r.state()).isEqualTo(State.LIVE);
        assertThat(r.problems()).hasSize(4).allMatch(p -> p.level() == Level.WARNING);
    }

    @Test
    void vip_with_every_episode_free_is_flagged() {
        var in = new Input("PUBLIC", "ROMANCE", "c", "b", "v", true, 5, null, List.of(true, true, true), 0, 0);
        assertThat(SeriesReadiness.check(in).problems())
                .extracting(Problem::message)
                .containsExactly("VIP is on, but all 3 episodes are within the free ones");
    }
}
