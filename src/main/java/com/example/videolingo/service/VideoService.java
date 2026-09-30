package com.example.videolingo.service;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.UpdateVideoRequest;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.dto.VideoStatisticsResponse;

import java.util.List;

public interface VideoService {

    PageResponse<VideoResponse> listVideos(VideoFilterRequest filter);

    // Works for trashed videos too, so the trash list can open them.
    VideoResponse getVideo(Long id);

    VideoResponse updateVideo(Long id, UpdateVideoRequest request);

    VideoResponse updateStatus(Long id, boolean enabled);

    // Soft delete — moves the video to the trash.
    void deleteVideo(Long id);

    VideoResponse restoreVideo(Long id);

    // Permanently deletes one trashed video and frees its storage (file,
    // versions, dubs). Must already be in the trash. Cannot be undone.
    void purgeVideo(Long id);

    // Permanently deletes every trashed video and frees its storage (file,
    // versions, dubs). Returns how many were purged. Cannot be undone.
    int purgeTrash();

    VideoStatisticsResponse getStatistics(Long id, int days);

    // Adds tags (existing ones are ignored). At most Settings › Video maxTagsPerVideo per video.
    VideoResponse assignTags(Long id, List<Long> tagIds);

    VideoResponse removeTag(Long id, Long tagId);

    // Put aside, out of the active library — reversible, distinct from enabled and the trash.
    VideoResponse archiveVideo(Long id);

    VideoResponse unarchiveVideo(Long id);

    // Reassign to another user.
    VideoResponse moveOwner(Long id, Long newOwnerId);

    // A full copy — same file, metadata, categories and tags — owned by the acting admin, disabled until reviewed.
    VideoResponse duplicate(Long id, String actingUsername);
}
