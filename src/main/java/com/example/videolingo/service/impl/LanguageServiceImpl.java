package com.example.videolingo.service.impl;

import com.example.videolingo.dto.LanguageFilterRequest;
import com.example.videolingo.dto.LanguageRequest;
import com.example.videolingo.dto.LanguageResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.Language;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.util.PageableUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LanguageServiceImpl implements LanguageService {

    private static final Set<String> SORTABLE =
            Set.of("id", "code", "name", "nativeName", "enabled", "isDefault", "createdAt", "updatedAt");

    private final LanguageRepository languageRepository;
    private final VideoRepository videoRepository;
    private final TranscriptRepository transcriptRepository;

    // ── Read ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LanguageResponse> list(LanguageFilterRequest filter) {
        List<Specification<Language>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%"
                    + TranscriptServiceImpl.escapeLike(filter.getSearch().trim().toLowerCase()) + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("nativeName")), pattern, '\\')));
        }
        if (filter.getEnabled() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("enabled"), filter.getEnabled()));
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "name";
        Page<Language> page = languageRepository.findAll(
                Specification.allOf(conditions),
                PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder()));

        // Usage for the whole page in two grouped queries, not two per row.
        List<String> codes = page.getContent().stream()
                .map(l -> l.getCode().toLowerCase(Locale.ROOT))
                .toList();
        Map<String, Long> videos = usage(codes.isEmpty() ? List.of() : videoRepository.countByLanguages(codes));
        Map<String, Long> transcripts =
                usage(codes.isEmpty() ? List.of() : transcriptRepository.countByLanguages(codes));
        return PageResponse.of(page.map(l -> toAdminResponse(
                l,
                videos.getOrDefault(l.getCode().toLowerCase(Locale.ROOT), 0L),
                transcripts.getOrDefault(l.getCode().toLowerCase(Locale.ROOT), 0L))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LanguageResponse> catalog() {
        return languageRepository.findAllByOrderByNameAsc().stream()
                .map(LanguageServiceImpl::toPublicResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LanguageResponse get(Long id) {
        return toAdminResponse(find(id));
    }

    // ── Write ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LanguageResponse create(LanguageRequest request) {
        String code = canonicalCode(request.getCode());
        if (languageRepository.existsByCodeIgnoreCase(code)) {
            throw new AppException(HttpStatus.CONFLICT, "Language '" + code + "' already exists");
        }
        Language language = languageRepository.save(Language.builder()
                .code(code)
                .name(request.getName().strip())
                .nativeName(blankToNull(request.getNativeName()))
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build());
        return toAdminResponse(language);
    }

    @Override
    @Transactional
    public LanguageResponse update(Long id, LanguageRequest request) {
        Language language = find(id);
        String code = canonicalCode(request.getCode());
        if (!code.equals(language.getCode())) {
            if (languageRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
                throw new AppException(HttpStatus.CONFLICT, "Language '" + code + "' already exists");
            }
            // Case-only changes ("pt-br" → "pt-BR") are fine: references match case-insensitively.
            boolean onlyCase = code.equalsIgnoreCase(language.getCode());
            long inUse = videoCount(language) + transcriptCount(language);
            if (!onlyCase && inUse > 0) {
                throw new AppException(
                        HttpStatus.CONFLICT,
                        "The code can't change while " + describeUsage(language) + " use '" + language.getCode()
                                + "' — create a new language instead");
            }
            language.setCode(code);
        }
        language.setName(request.getName().strip());
        language.setNativeName(blankToNull(request.getNativeName()));
        return toAdminResponse(languageRepository.save(language));
    }

    @Override
    @Transactional
    public LanguageResponse setEnabled(Long id, boolean enabled) {
        Language language = find(id);
        if (!enabled && language.isDefault()) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "The default language can't be disabled — make another language the default first");
        }
        language.setEnabled(enabled);
        return toAdminResponse(languageRepository.save(language));
    }

    @Override
    @Transactional
    public LanguageResponse setDefault(Long id) {
        Language language = find(id);
        if (!language.isEnabled()) {
            throw new AppException(
                    HttpStatus.CONFLICT, "Enable " + language.getName() + " before making it the default");
        }
        if (!language.isDefault()) {
            languageRepository.clearDefault();
            // clearDefault detached everything; re-read before changing it.
            language = find(id);
            language.setDefault(true);
            language = languageRepository.save(language);
        }
        return toAdminResponse(language);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Language language = find(id);
        String blocked = deleteBlockedReason(language, videoCount(language), transcriptCount(language));
        if (blocked != null) {
            throw new AppException(HttpStatus.CONFLICT, blocked);
        }
        languageRepository.delete(language);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolve(String code, boolean requireEnabled) {
        if (code == null || code.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Language is required");
        }
        Language language = languageRepository
                .findByCodeIgnoreCase(code.strip())
                .orElseThrow(() -> new AppException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown language '" + code.strip() + "' — add it under Languages first"));
        if (requireEnabled && !language.isEnabled()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, language.getName() + " is disabled — enable it under Languages to use it");
        }
        return language.getCode();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Language find(Long id) {
        return languageRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Language not found with id: " + id));
    }

    /**
     * BCP 47 casing: language lowercase, 4-letter script Title-case, 2-letter or
     * 3-digit region uppercase, anything else lowercase — "PT-br" → "pt-BR",
     * "zh-hant" → "zh-Hant".
     */
    static String canonicalCode(String raw) {
        String[] parts = raw.strip().split("-");
        StringBuilder out = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            out.append('-');
            if (p.length() == 4 && p.chars().allMatch(Character::isLetter)) {
                out.append(p.substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(p.substring(1).toLowerCase(Locale.ROOT));
            } else if (p.length() == 2 || (p.length() == 3 && p.chars().allMatch(Character::isDigit))) {
                out.append(p.toUpperCase(Locale.ROOT));
            } else {
                out.append(p.toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static Map<String, Long> usage(List<VideoRepository.LanguageUsage> rows) {
        return rows.stream()
                .collect(Collectors.toMap(
                        r -> r.getLanguage().toLowerCase(Locale.ROOT),
                        VideoRepository.LanguageUsage::getCount,
                        Long::sum));
    }

    private long videoCount(Language l) {
        return usage(videoRepository.countByLanguages(List.of(l.getCode().toLowerCase(Locale.ROOT)))).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private long transcriptCount(Language l) {
        return usage(transcriptRepository.countByLanguages(List.of(l.getCode().toLowerCase(Locale.ROOT))))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private String describeUsage(Language l) {
        return describeUsage(videoCount(l), transcriptCount(l));
    }

    static String describeUsage(long videos, long transcripts) {
        List<String> parts = new ArrayList<>();
        if (videos > 0) {
            parts.add(videos + (videos == 1 ? " video" : " videos"));
        }
        if (transcripts > 0) {
            parts.add(transcripts + (transcripts == 1 ? " transcript" : " transcripts"));
        }
        return String.join(" and ", parts);
    }

    static String deleteBlockedReason(Language l, long videos, long transcripts) {
        if (l.isDefault()) {
            return "The default language can't be deleted — make another language the default first";
        }
        if (videos + transcripts > 0) {
            return "In use by " + describeUsage(videos, transcripts)
                    + " — disable it instead, so existing content keeps its language";
        }
        return null;
    }

    private LanguageResponse toAdminResponse(Language l) {
        return toAdminResponse(l, videoCount(l), transcriptCount(l));
    }

    private static LanguageResponse toAdminResponse(Language l, long videos, long transcripts) {
        LanguageResponse r = toPublicResponse(l);
        r.setVideoCount(videos);
        r.setTranscriptCount(transcripts);
        r.setDeleteBlockedReason(deleteBlockedReason(l, videos, transcripts));
        r.setDisableBlockedReason(l.isDefault() ? "The default language can't be disabled" : null);
        return r;
    }

    private static LanguageResponse toPublicResponse(Language l) {
        return LanguageResponse.builder()
                .id(l.getId())
                .code(l.getCode())
                .name(l.getName())
                .nativeName(l.getNativeName())
                .enabled(l.isEnabled())
                .isDefault(l.isDefault())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
