package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A saved copy of a subtitle track's or transcript's full content, taken
// after every save that changes it (see RevisionService). The newest one
// matches what's live; older ones can be compared and restored. `snapshot`
// is JSON — RevisionService.SubtitleSnapshot / TranscriptSnapshot.
@Entity
@Table(name = "content_revisions", indexes = @Index(name = "idx_content_revisions_entity", columnList = "entity_type, entity_id, number"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentRevision {

    public enum EntityType { SUBTITLE, TRANSCRIPT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // 1, 2, 3… per entity; never reused, even after old ones are pruned.
    @Column(nullable = false)
    private int number;

    // "Edited", "Regenerated from transcript #3", "Restored revision 4"…
    @Column(nullable = false, length = 200)
    private String summary;

    // Cues or segments in the snapshot.
    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(nullable = false, columnDefinition = "text")
    private String snapshot;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
