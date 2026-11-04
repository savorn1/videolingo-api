package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A collection's settings as a series on the public streaming site (RuengFlix).
// Off by default: a collection only appears there once `enabled` is turned on.
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamingSeries {

    @Column(name = "streaming", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "series_category", length = 20)
    private SeriesCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "series_status", nullable = false, length = 20)
    @Builder.Default
    private SeriesStatus status = SeriesStatus.ONGOING;

    // A VIP series asks for payment after its first `freeEpisodes` episodes.
    @Column(name = "vip", nullable = false)
    @Builder.Default
    private boolean vip = false;

    @Column(name = "free_episodes", nullable = false)
    @Builder.Default
    private int freeEpisodes = 3;

    // Length of the "previously on" recap at the start of episodes 2+, for the site's Skip recap button.
    @Column(name = "recap_seconds", nullable = false)
    @Builder.Default
    private int recapSeconds = 0;

    // English / romanised words so Latin-keyboard searches ("summer love", "sneha") find Khmer titles.
    @Column(name = "search_keywords", length = 200)
    private String searchKeywords;

    @Column(name = "region", length = 40)
    private String region;

    @Column(name = "release_year")
    private Integer releaseYear;

    // Comma separated, at most five.
    @Column(name = "genres", length = 400)
    private String genres;

    // Wide hero artwork; the cover (5:7 poster) is the collection's coverUrl.
    @Column(name = "banner_url", length = 1000)
    private String bannerUrl;

    // 9:16 artwork for the vertical Shorts feed.
    @Column(name = "vertical_url", length = 1000)
    private String verticalUrl;
}
