package com.example.videolingo.repository;

import com.example.videolingo.entity.Video;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoRepository extends JpaRepository<Video, Long>, JpaSpecificationExecutor<Video> {

    /** Same platform video, or the same link — used to warn about adding a video twice. */
    @org.springframework.data.jpa.repository.Query(
            """
            select v from Video v where (v.source = :source and v.externalId = :externalId) or v.videoUrl = :url order by v.id
            """)
    java.util.List<Video> findDuplicates(
            @org.springframework.data.repository.query.Param("source") com.example.videolingo.entity.VideoSource source,
            @org.springframework.data.repository.query.Param("externalId") String externalId,
            @org.springframework.data.repository.query.Param("url") String url);

    /** Everything currently in the trash — used to clear it out. */
    List<Video> findByDeletedAtIsNotNull();

    interface LanguageUsage {
        String getLanguage();

        long getCount();
    }

    // Trashed videos count too — restoring one must not bring back a dangling code.
    @Query(
            "select v.language as language, count(v) as count from Video v where lower(v.language) in :codes group by v.language")
    List<LanguageUsage> countByLanguages(@Param("codes") Collection<String> codes);

    interface CategoryUsage {
        Long getCategoryId();

        long getCount();
    }

    // Live videos only — trashed ones don't count toward "N videos" in the UI.
    @Query(
            value =
                    """
            select vc.category_id as categoryId, count(*) as count
            from video_categories vc join videos v on v.id = vc.video_id
            where v.deleted_at is null and vc.category_id in (:categoryIds)
            group by vc.category_id
            """,
            nativeQuery = true)
    List<CategoryUsage> countLiveByCategoryIds(@Param("categoryIds") Collection<Long> categoryIds);

    interface TagUsage {
        Long getTagId();

        long getCount();
    }

    @Query(
            value =
                    """
            select vt.tag_id as tagId, count(*) as count
            from video_tags vt join videos v on v.id = vt.video_id
            where v.deleted_at is null and vt.tag_id in (:tagIds)
            group by vt.tag_id
            """,
            nativeQuery = true)
    List<TagUsage> countLiveByTagIds(@Param("tagIds") Collection<Long> tagIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from video_tags where tag_id = :tagId", nativeQuery = true)
    int detachTag(@Param("tagId") Long tagId);

    // Removes a category from every video (trashed ones included). Returns rows removed.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from video_categories where category_id = :categoryId", nativeQuery = true)
    int detachCategory(@Param("categoryId") Long categoryId);
}
