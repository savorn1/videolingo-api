package com.example.videolingo.repository;

import com.example.videolingo.entity.TranscriptSegment;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByTranscriptIdOrderByPositionAsc(Long transcriptId);

    @Modifying
    @Query("delete from TranscriptSegment s where s.transcriptId = :transcriptId")
    void deleteByTranscriptId(@Param("transcriptId") Long transcriptId);

    interface SearchHit {
        Long getSegmentId();

        int getPosition();

        long getStartMs();

        long getEndMs();

        String getText();

        Long getTranscriptId();

        String getLanguage();

        Long getVideoId();

        String getVideoTitle();
    }

    // Case-insensitive substring match across every transcript. `pattern`
    // must already be LIKE-escaped with '\' (see TranscriptServiceImpl).
    @Query(value = """
            select s.id as segmentId, s.position as position, s.startMs as startMs, s.endMs as endMs, s.text as text,
                   t.id as transcriptId, t.language as language, t.videoId as videoId, v.title as videoTitle
            from TranscriptSegment s join Transcript t on t.id = s.transcriptId join Video v on v.id = t.videoId
            where lower(s.text) like :pattern escape '\\'
              and (:videoId is null or t.videoId = :videoId)
              and (:language is null or t.language = :language)
            order by t.id desc, s.position asc
            """, countQuery = """
            select count(s) from TranscriptSegment s join Transcript t on t.id = s.transcriptId
            where lower(s.text) like :pattern escape '\\'
              and (:videoId is null or t.videoId = :videoId)
              and (:language is null or t.language = :language)
            """)
    Page<SearchHit> search(
            @Param("pattern") String pattern,
            @Param("videoId") Long videoId,
            @Param("language") String language,
            Pageable pageable);
}
