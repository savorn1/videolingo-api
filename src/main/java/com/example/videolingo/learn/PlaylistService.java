package com.example.videolingo.learn;

import com.example.videolingo.dto.CollectionFilterRequest;
import com.example.videolingo.dto.CollectionRequest;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.CollectionVisibility;
import com.example.videolingo.entity.VideoCollection;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.VideoCollectionRepository;
import com.example.videolingo.service.CollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

// A learner's own playlists — private collections they own, built on top of
// CollectionService (which already knows how to manage a collection's
// videos). Always CollectionVisibility.PRIVATE: LearnService.collection()'s
// owner-visibility rule already lets an owner view their own private
// collection, and the existing /collections/{id}/play player already works
// on any collection reachable that way, so no new "view" endpoint is needed.
@Service
@RequiredArgsConstructor
public class PlaylistService {

    private final CollectionService collectionService;
    private final VideoCollectionRepository collectionRepository;

    @Transactional(readOnly = true)
    public PageResponse<CollectionResponse> mine(Long userId, int page, int size) {
        CollectionFilterRequest filter = new CollectionFilterRequest();
        filter.setOwnerId(userId);
        filter.setVisibility(CollectionVisibility.PRIVATE);
        filter.setSortBy("updatedAt");
        filter.setSortOrder("desc");
        filter.setPage(page);
        filter.setSize(Math.max(1, Math.min(size, 100)));
        return collectionService.list(filter);
    }

    @Transactional
    public CollectionResponse create(Long userId, String title, String username) {
        CollectionRequest request = new CollectionRequest();
        request.setTitle(title);
        request.setVisibility(CollectionVisibility.PRIVATE);
        request.setOwnerId(userId);
        return collectionService.create(request, username);
    }

    @Transactional
    public CollectionResponse rename(Long userId, Long id, String title) {
        requireOwned(id, userId);
        CollectionRequest request = new CollectionRequest();
        request.setTitle(title);
        request.setVisibility(CollectionVisibility.PRIVATE);
        return collectionService.update(id, request);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        collectionService.delete(id);
    }

    @Transactional
    public CollectionResponse addVideo(Long userId, Long id, Long videoId, String username) {
        requireOwned(id, userId);
        collectionService.addVideos(id, List.of(videoId), username);
        return collectionService.get(id);
    }

    @Transactional
    public CollectionResponse removeVideo(Long userId, Long id, Long videoId) {
        requireOwned(id, userId);
        return collectionService.removeVideo(id, videoId);
    }

    private VideoCollection requireOwned(Long id, Long userId) {
        VideoCollection c = collectionRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Playlist not found"));
        if (!Objects.equals(c.getOwnerId(), userId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "That's not your playlist");
        }
        return c;
    }
}
