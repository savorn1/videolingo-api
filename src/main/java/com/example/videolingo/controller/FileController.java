package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.FileUploadResponse;
import com.example.videolingo.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Generic S3-backed file/image upload — hands the caller back a URL and key
// to store wherever it needs to (a user profile, a document record, etc.).
// No metadata is persisted here; that's the caller's responsibility.
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FileUploadResponse>> upload(@RequestParam("file") MultipartFile file,
                                                                    @RequestParam(value = "folder", required = false) String folder) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("File uploaded", fileStorageService.upload(file, folder)));
    }
}
