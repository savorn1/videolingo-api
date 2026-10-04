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

// One timed line of a transcript (one subtitle cue). Segments are replaced
// wholesale on every save, so `position` is simply 0..n-1 in start order.
@Entity
@Table(
        name = "transcript_segments",
        indexes = {@Index(name = "idx_transcript_segments_transcript", columnList = "transcript_id, position")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transcript_id", nullable = false)
    private Long transcriptId;

    @Column(nullable = false)
    private int position;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(length = 64)
    private String speaker;
}
