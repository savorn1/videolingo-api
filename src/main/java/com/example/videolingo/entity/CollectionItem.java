package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One video's place in a collection. A video appears at most once per
// collection; `position` is 0..n-1 and is compacted after a removal.
@Entity
@Table(
        name = "collection_items",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_collection_items_collection_video",
                        columnNames = {"collection_id", "video_id"}),
        indexes = {
            @Index(name = "idx_collection_items_order", columnList = "collection_id, position"),
            @Index(name = "idx_collection_items_video", columnList = "video_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collection_id", nullable = false)
    private Long collectionId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(nullable = false)
    private int position;

    // Optional grouping label ("Week 1") shown as a heading above this video.
    @Column(length = 200)
    private String section;

    @Column(name = "added_by", length = 100)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
    }
}
