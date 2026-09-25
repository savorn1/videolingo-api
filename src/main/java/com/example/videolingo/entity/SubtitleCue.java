package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One cue of a subtitle track. Replaced wholesale on every save, so
// `position` is 0..n-1 in start order. `text` keeps its '\n' line breaks.
@Entity
@Table(name = "subtitle_cues", indexes = @Index(name = "idx_subtitle_cues_subtitle", columnList = "subtitle_id, position"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubtitleCue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subtitle_id", nullable = false)
    private Long subtitleId;

    @Column(nullable = false)
    private int position;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(nullable = false, columnDefinition = "text")
    private String text;
}
