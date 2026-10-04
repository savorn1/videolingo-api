package com.example.videolingo.service;

import com.example.videolingo.entity.VideoMarker;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.VideoMarkerRepository;
import com.example.videolingo.repository.VideoRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Named timestamps on a video's timeline (see VideoMarker). Plain CRUD —
// no processing job involved.
@Service
@RequiredArgsConstructor
public class VideoMarkerService {

    public record MarkerResponse(
            Long id, long atMs, String label, String color, String createdBy, LocalDateTime createdAt) {}

    public record MarkerRequest(long atMs, String label, String color) {}

    private final VideoRepository videoRepository;
    private final VideoMarkerRepository markerRepository;

    @Transactional(readOnly = true)
    public List<MarkerResponse> list(Long videoId) {
        requireVideo(videoId);
        return markerRepository.findByVideoIdOrderByAtMsAsc(videoId).stream()
                .map(VideoMarkerService::toResponse)
                .toList();
    }

    @Transactional
    public MarkerResponse create(Long videoId, MarkerRequest request, String actingUsername) {
        requireVideo(videoId);
        String label = validated(request);
        VideoMarker marker = markerRepository.save(VideoMarker.builder()
                .videoId(videoId)
                .atMs(request.atMs())
                .label(label)
                .color(blankToNull(request.color()))
                .createdBy(actingUsername)
                .build());
        return toResponse(marker);
    }

    @Transactional
    public MarkerResponse update(Long videoId, Long markerId, MarkerRequest request) {
        VideoMarker marker = findMarker(videoId, markerId);
        String label = validated(request);
        marker.setAtMs(request.atMs());
        marker.setLabel(label);
        marker.setColor(blankToNull(request.color()));
        return toResponse(markerRepository.save(marker));
    }

    @Transactional
    public void remove(Long videoId, Long markerId) {
        markerRepository.delete(findMarker(videoId, markerId));
    }

    // Called when the video itself is being permanently purged.
    @Transactional
    public void purgeAllForVideo(Long videoId) {
        markerRepository.deleteAll(markerRepository.findByVideoIdOrderByAtMsAsc(videoId));
    }

    private VideoMarker findMarker(Long videoId, Long markerId) {
        return markerRepository
                .findById(markerId)
                .filter(m -> m.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Marker not found"));
    }

    private void requireVideo(Long videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId);
        }
    }

    private static String validated(MarkerRequest request) {
        if (request.atMs() < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The marker can't be before the beginning");
        }
        String label = request.label() == null ? "" : request.label().strip();
        if (label.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Give the marker a label");
        }
        if (label.length() > 200) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The label is too long (200 characters max)");
        }
        return label;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static MarkerResponse toResponse(VideoMarker m) {
        return new MarkerResponse(
                m.getId(), m.getAtMs(), m.getLabel(), m.getColor(), m.getCreatedBy(), m.getCreatedAt());
    }
}
