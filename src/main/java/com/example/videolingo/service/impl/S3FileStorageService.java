package com.example.videolingo.service.impl;

import com.example.videolingo.dto.FileUploadResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.FileStorageService;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

    private static final String DEFAULT_FOLDER = "uploads";

    private final S3Client s3Client;
    private final SettingsService settings;

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.public-endpoint}")
    private String publicEndpoint;

    @Override
    public FileUploadResponse upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "File is required");
        }
        Settings.Storage limits = settings.storage();
        if (file.getSize() > limits.maxUploadMb() * 1024L * 1024L) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Files can be at most " + limits.maxUploadMb() + " MB");
        }
        if (!typeAllowed(file.getContentType(), limits.allowedUploadTypes())) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "This file type (" + (file.getContentType() == null ? "unknown" : file.getContentType())
                            + ") isn't allowed — accepted: " + String.join(", ", limits.allowedUploadTypes()));
        }

        String originalFilename = file.getOriginalFilename();
        String key = sanitizeFolder(folder) + "/" + UUID.randomUUID() + extensionOf(originalFilename);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        } catch (S3Exception e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Failed to store file: " + e.getMessage());
        }

        return FileUploadResponse.builder()
                .key(key)
                .url(publicEndpoint + "/" + bucket + "/" + key)
                .fileName(originalFilename)
                .contentType(file.getContentType())
                .size(file.getSize())
                .build();
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    // Empty list = anything; "image/*" matches the whole family.
    static boolean typeAllowed(String contentType, java.util.List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
        for (String a : allowed) {
            if (a.endsWith("/*") ? type.startsWith(a.substring(0, a.length() - 1)) : type.equals(a)) {
                return true;
            }
        }
        return false;
    }

    // Keys are namespaced by an unauthenticated, caller-supplied folder — keep
    // it to a plain path segment so it can't escape into an unrelated prefix
    // or produce a malformed key.
    private String sanitizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return DEFAULT_FOLDER;
        }
        String cleaned = folder.strip().replaceAll("[^a-zA-Z0-9/_-]", "");
        cleaned = cleaned.replaceAll("\\.\\.", "").replaceAll("^/+|/+$", "");
        return cleaned.isBlank() ? DEFAULT_FOLDER : cleaned;
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.'));
    }
}
