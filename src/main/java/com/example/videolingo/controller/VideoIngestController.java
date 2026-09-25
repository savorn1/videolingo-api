package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.VideoIngestDtos.CreateVideoRequest;
import com.example.videolingo.dto.VideoIngestDtos.DetectLanguageRequest;
import com.example.videolingo.dto.VideoIngestDtos.InspectRequest;
import com.example.videolingo.dto.VideoIngestDtos.InspectResponse;
import com.example.videolingo.dto.VideoIngestDtos.LanguageGuess;
import com.example.videolingo.dto.VideoIngestDtos.UploadRequest;
import com.example.videolingo.dto.VideoIngestDtos.UploadTicket;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.ingest.VideoIngestService;
import com.example.videolingo.ingest.VideoInspector;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Add Video, under module "videos" (all POST = WRITE): inspect a link, detect
// a language, get a signed upload, then create the video.
@RestController
@RequestMapping("/api/admin/videos")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class VideoIngestController {

    private final VideoIngestService ingest;

    @PostMapping("/inspect")
    public ResponseEntity<ApiResponse<InspectResponse>> inspect(@Valid @RequestBody InspectRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(ingest.inspect(request.getUrl())));
        } catch (VideoInspector.InspectException e) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    @PostMapping("/detect-language")
    public ResponseEntity<ApiResponse<LanguageGuess>> detectLanguage(@Valid @RequestBody DetectLanguageRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ingest.detectLanguage(request.getTitle(), request.getDescription())));
    }

    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<UploadTicket>> upload(@Valid @RequestBody UploadRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ingest.presignUpload(request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VideoResponse>> create(@Valid @RequestBody CreateVideoRequest request, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Video added", ingest.create(request, auth.getName())));
    }
}
