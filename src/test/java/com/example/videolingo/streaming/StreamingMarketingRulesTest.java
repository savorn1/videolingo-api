package com.example.videolingo.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.DubService;
import com.example.videolingo.streaming.StreamingMarketingAdminService.AdRequest;
import com.example.videolingo.streaming.StreamingMarketingAdminService.PromotionRequest;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StreamingMarketingRulesTest {

    @Test
    void adNeedsANameAVideoLinkAndSaneNumbers() {
        AdRequest ok = StreamingMarketingAdminService.validAd(
                new AdRequest(" Sponsor ", "https://cdn.example/ad.mp4", "", 15, null, null), null);
        assertThat(ok.title()).isEqualTo("Sponsor");
        assertThat(ok.clickUrl()).isNull();
        assertThat(ok.active()).isTrue();
        assertThat(ok.weight()).isEqualTo(1);

        assertThatThrownBy(() -> StreamingMarketingAdminService.validAd(
                        new AdRequest("x", "ftp://nope", null, 15, true, 1), null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> StreamingMarketingAdminService.validAd(
                        new AdRequest("x", "https://a.b/v.mp4", null, 90, true, 1), null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> StreamingMarketingAdminService.validAd(
                        new AdRequest("x", "https://a.b/v.mp4", "javascript:alert(1)", 15, true, 1), null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void promotionsAreBoundedAndComplete() {
        LocalDateTime now = LocalDateTime.now();
        StreamingMarketingAdminService.validPromotion(
                new PromotionRequest("COIN_BONUS", "Weekend", null, 30, now, now.plusDays(2)));
        assertThatThrownBy(() -> StreamingMarketingAdminService.validPromotion(
                        new PromotionRequest("FREE_SERIES", "Free", null, null, now, now.plusDays(1))))
                .hasMessageContaining("series");
        assertThatThrownBy(() -> StreamingMarketingAdminService.validPromotion(
                        new PromotionRequest("COIN_BONUS", "Big", null, 500, now, now.plusDays(1))))
                .hasMessageContaining("200%");
        assertThatThrownBy(() -> StreamingMarketingAdminService.validPromotion(
                        new PromotionRequest("COIN_BONUS", "Long", null, 10, now, now.plusDays(40))))
                .hasMessageContaining("31 days");
        assertThatThrownBy(() -> StreamingMarketingAdminService.validPromotion(
                        new PromotionRequest("COIN_BONUS", "Past", null, 10, now.minusDays(3), now.minusDays(1))))
                .hasMessageContaining("over");
    }

    @Test
    void clipCutSeeksFastAndEncodesForPhones() {
        var args = ClipWorker.cutArgs("ffmpeg", "https://x/v.mp4", 25, 15, Path.of("/tmp/c.mp4"));
        assertThat(String.join(" ", args))
                .contains("-ss 25 -i https://x/v.mp4 -t 15")
                .contains("libx264")
                .contains("+faststart")
                .endsWith("/tmp/c.mp4");
    }

    @Test
    void uploadedTrackLengthMustRoughlyMatchTheVideo() {
        assertThat(DubService.lengthMismatch(600, 603_000)).isFalse();
        assertThat(DubService.lengthMismatch(600, 640_000)).isTrue();
        assertThat(DubService.lengthMismatch(null, 640_000)).isFalse();
    }
}
