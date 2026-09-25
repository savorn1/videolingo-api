package com.example.videolingo.service;

import com.example.videolingo.dto.LanguageFilterRequest;
import com.example.videolingo.dto.LanguageRequest;
import com.example.videolingo.dto.LanguageResponse;
import com.example.videolingo.dto.PageResponse;

import java.util.List;

public interface LanguageService {

    PageResponse<LanguageResponse> list(LanguageFilterRequest filter);

    // Every language (enabled or not), without usage counts — the public
    // catalog the app uses for labels and pickers.
    List<LanguageResponse> catalog();

    LanguageResponse get(Long id);

    LanguageResponse create(LanguageRequest request);

    LanguageResponse update(Long id, LanguageRequest request);

    LanguageResponse setEnabled(Long id, boolean enabled);

    LanguageResponse setDefault(Long id);

    void delete(Long id);

    /**
     * Resolves a code sent by a client to the stored canonical code, or throws
     * 400 if it isn't a known language. `requireEnabled` is false only when the
     * record already had this language — so disabling a language doesn't make
     * its existing videos/transcripts impossible to save.
     */
    String resolve(String code, boolean requireEnabled);
}
