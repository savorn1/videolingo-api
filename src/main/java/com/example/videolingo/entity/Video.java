package com.example.videolingo.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

// A learning video. Two independent switches control visibility:
//   enabled   — an admin's on/off toggle (e.g. pulled pending review); the
//               video stays in the normal list, just marked Disabled.
//   deletedAt — soft delete; the video moves to the trash list and can be
//               restored. Nothing is ever hard-deleted from the admin UI.
// Technical metadata (duration, resolution, size, format) is stored as
// reported at upload time — it isn't re-probed from the file.
@Entity
@Table(
        name = "videos",
        indexes = {
            @Index(name = "idx_videos_owner_id", columnList = "owner_id"),
            @Index(name = "idx_videos_deleted_at", columnList = "deleted_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    // Uploader. A plain id rather than a @ManyToOne, same as
    // User.customRoleId — deleting a user leaves their videos in place.
    @Column(name = "owner_id")
    private Long ownerId;

    // ISO 639-1 code of the spoken language, e.g. "en", "ja", "km".
    @Column(length = 10)
    private String language;

    @Column(name = "video_url", nullable = false, length = 1000)
    private String videoUrl;

    // S3 object key when the file lives in our bucket; null for external URLs.
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    // The original link of a video imported into our bucket (see DOWNLOAD
    // jobs); null for everything else.
    @Column(name = "imported_from", length = 1000)
    private String importedFrom;

    // Where the media lives (see VideoSource). Null on rows from before this
    // column existed: read as UPLOAD when storageKey is set, else URL.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VideoSource source;

    // The platform's own id (YouTube "dQw4w9WgXcQ", Vimeo "1084537", Facebook
    // numeric id) — used to spot the same video being added twice.
    @Column(name = "external_id", length = 64)
    private String externalId;

    // Channel / page / uploader name reported by the platform.
    @Column(name = "source_author", length = 200)
    private String sourceAuthor;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    private Integer width;

    private Integer height;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    // Put aside, out of the active library — distinct from enabled (a quick
    // on/off) and deletedAt (the trash). Learners can't watch it either way.
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VideoVisibility visibility = VideoVisibility.PUBLIC;

    // Categories this video is filed under. Plain ids (like ownerId) in a join
    // table — a category can be deleted without touching the video row.
    // BatchSize loads the sets for a whole list page in a few queries.
    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "video_categories", joinColumns = @JoinColumn(name = "video_id"))
    @Column(name = "category_id", nullable = false)
    @BatchSize(size = 50)
    private Set<Long> categoryIds = new LinkedHashSet<>();

    // Tags on this video — same join-table shape as categoryIds. Changed only
    // through the assign/remove endpoints (VideoService.assignTags/removeTag).
    @Builder.Default
    @ElementCollection
    @CollectionTable(name = "video_tags", joinColumns = @JoinColumn(name = "video_id"))
    @Column(name = "tag_id", nullable = false)
    @BatchSize(size = 50)
    private Set<Long> tagIds = new LinkedHashSet<>();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }
}
