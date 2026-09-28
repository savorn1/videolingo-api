package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.entity.UserPreference;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.UserPreferenceRepository;
import com.example.videolingo.security.CurrentUserResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// The signed-in user's own preferences: one JSON object, replaced as a whole.
// Plain Maps in and out (not a JsonNode): the web layer's JSON library isn't
// the ObjectMapper used here, and a Map serialises the same under either.
@RestController
@RequestMapping("/api/me/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    /** Plenty for settings; stops it being used as general storage. */
    static final int MAX_BYTES = 32 * 1024;

    private final UserPreferenceRepository repository;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(Authentication authentication) {
        Long userId = currentUser.requireUserId(authentication);
        Map<String, Object> data = repository.findById(userId).map(p -> parse(p.getData())).orElseGet(LinkedHashMap::new);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> put(@RequestBody Map<String, Object> body, Authentication authentication) {
        if (body == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Preferences must be a JSON object");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Preferences aren't valid JSON");
        }
        if (json.length() > MAX_BYTES) {
            throw new AppException(HttpStatus.PAYLOAD_TOO_LARGE, "Preferences are too large");
        }
        Long userId = currentUser.requireUserId(authentication);
        UserPreference pref = repository.findById(userId).orElseGet(() -> UserPreference.builder().userId(userId).build());
        pref.setData(json);
        repository.save(pref);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }
}
