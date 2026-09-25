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

    VideoStatisticsResponse getStatistics(Long id, int days);

    // Adds tags (existing ones are ignored). At most Settings › Video maxTagsPerVideo per video.
    VideoResponse assignTags(Long id, List<Long> tagIds);

    VideoResponse removeTag(Long id, Long tagId);
}
