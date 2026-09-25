package com.example.videolingo.service;

import com.example.videolingo.dto.FileUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    FileUploadResponse upload(MultipartFile file, String folder);

    void delete(String key);
}
