package com.example.videolingo.controller;

import com.example.videolingo.apikey.ApiKeyAuthenticationToken;
import com.example.videolingo.apikey.ApiKeyService;
import com.example.videolingo.apikey.ApiKeyService.ApiKeyResponse;
import com.example.videolingo.apikey.ApiKeyService.CreateRequest;
import com.example.videolingo.apikey.ApiKeyService.CreatedKey;
import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.security.CurrentUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// API keys, module "api-keys" (GET = READ, the rest WRITE). A key is created
// for, and acts as, whoever creates it. Admins see and can revoke every key.
// Keys can't be managed with a key — a leaked key mustn't be able to mint more.
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final CurrentUserResolver currentUser;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                apiKeyService.list(requireSignedIn(authentication), currentUser.isAdmin(authentication))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatedKey>> create(
            @Valid @RequestBody CreateRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "API key created — copy it now, it won't be shown again",
                        apiKeyService.create(request, requireSignedIn(authentication))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> revoke(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "API key revoked",
                apiKeyService.revoke(id, requireSignedIn(authentication), currentUser.isAdmin(authentication))));
    }

    private String requireSignedIn(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (authentication instanceof ApiKeyAuthenticationToken) {
            throw new AppException(HttpStatus.FORBIDDEN, "API keys can't be managed with an API key — sign in instead");
        }
        return authentication.getName();
    }
}
