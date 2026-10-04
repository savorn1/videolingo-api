package com.example.videolingo.glossary;

import com.example.videolingo.dto.GlossaryDtos.ApplicableTerm;
import com.example.videolingo.dto.GlossaryDtos.GlossaryFilter;
import com.example.videolingo.dto.GlossaryDtos.GlossaryRequest;
import com.example.videolingo.dto.GlossaryDtos.GlossaryResponse;
import com.example.videolingo.dto.GlossaryDtos.TermDto;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.Glossary;
import com.example.videolingo.entity.GlossaryTerm;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.GlossaryRepository;
import com.example.videolingo.repository.GlossaryTermRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.impl.TranscriptServiceImpl;
import com.example.videolingo.util.PageableUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GlossaryService {

    private static final Set<String> SORTABLE =
            Set.of("id", "name", "sourceLanguage", "targetLanguage", "enabled", "termCount", "createdAt", "updatedAt");

    private final GlossaryRepository glossaryRepository;
    private final GlossaryTermRepository termRepository;
    private final LanguageService languageService;

    @Transactional(readOnly = true)
    public PageResponse<GlossaryResponse> list(GlossaryFilter filter) {
        List<Specification<Glossary>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%"
                    + TranscriptServiceImpl.escapeLike(
                            filter.getSearch().strip().toLowerCase(Locale.ROOT)) + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (filter.getSourceLanguage() != null && !filter.getSourceLanguage().isBlank()) {
            conditions.add((root, query, cb) -> cb.equal(
                    cb.lower(root.get("sourceLanguage")),
                    filter.getSourceLanguage().toLowerCase(Locale.ROOT)));
        }
        if (filter.getTargetLanguage() != null && !filter.getTargetLanguage().isBlank()) {
            conditions.add((root, query, cb) -> cb.equal(
                    cb.lower(root.get("targetLanguage")),
                    filter.getTargetLanguage().toLowerCase(Locale.ROOT)));
        }
        if (filter.getEnabled() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("enabled"), filter.getEnabled()));
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "name";
        Page<Glossary> page = glossaryRepository.findAll(
                Specification.allOf(conditions),
                PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder()));
        return PageResponse.of(page.map(g -> toResponse(g, null)));
    }

    @Transactional(readOnly = true)
    public GlossaryResponse get(Long id) {
        Glossary glossary = find(id);
        return toResponse(
                glossary,
                termRepository.findByGlossaryIdOrderByPositionAsc(id).stream()
                        .map(GlossaryService::toDto)
                        .toList());
    }

    @Transactional
    public GlossaryResponse create(GlossaryRequest request, String actingUsername) {
        String name = request.getName().strip();
        if (glossaryRepository.existsByNameIgnoreCase(name)) {
            throw new AppException(HttpStatus.CONFLICT, "A glossary named \"" + name + "\" already exists");
        }
        Glossary glossary =
                Glossary.builder().name(name).createdBy(actingUsername).build();
        apply(glossary, request, actingUsername, true);
        glossary = glossaryRepository.save(glossary);
        replaceTerms(glossary, request.getTerms());
        return get(glossaryRepository.save(glossary).getId());
    }

    @Transactional
    public GlossaryResponse update(Long id, GlossaryRequest request, String actingUsername) {
        Glossary glossary = find(id);
        String name = request.getName().strip();
        if (glossaryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new AppException(HttpStatus.CONFLICT, "A glossary named \"" + name + "\" already exists");
        }
        glossary.setName(name);
        boolean sameTarget = request.getTargetLanguage().strip().equalsIgnoreCase(glossary.getTargetLanguage());
        apply(glossary, request, actingUsername, !sameTarget);
        replaceTerms(glossary, request.getTerms());
        glossaryRepository.save(glossary);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        Glossary glossary = find(id);
        termRepository.deleteByGlossaryId(id);
        glossaryRepository.delete(glossary);
    }

    /**
     * The merged terms of every enabled glossary translating into `target`
     * (and from `source`, when given). Where two glossaries define the same
     * source term, the source-language-specific one wins, then the older one.
     */
    @Transactional(readOnly = true)
    public List<ApplicableTerm> applicable(String source, String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        List<Glossary> glossaries = source == null || source.isBlank()
                ? glossaryRepository.applicableToTarget(target.strip())
                : glossaryRepository.applicable(source.strip(), target.strip());
        if (glossaries.isEmpty()) {
            return List.of();
        }
        Map<Long, Glossary> byId = glossaries.stream().collect(Collectors.toMap(Glossary::getId, Function.identity()));
        Map<Long, List<GlossaryTerm>> terms =
                termRepository.findByGlossaryIdInOrderByGlossaryIdAscPositionAsc(byId.keySet()).stream()
                        .collect(Collectors.groupingBy(GlossaryTerm::getGlossaryId));
        Set<String> seen = new HashSet<>();
        List<ApplicableTerm> out = new ArrayList<>();
        for (Glossary g : glossaries) {
            for (GlossaryTerm t : terms.getOrDefault(g.getId(), List.of())) {
                if (seen.add(t.getSource().toLowerCase(Locale.ROOT))) {
                    out.add(new ApplicableTerm(
                            t.getSource(),
                            t.getTarget(),
                            t.isDoNotTranslate(),
                            t.isCaseSensitive(),
                            t.getNote(),
                            g.getId(),
                            g.getName()));
                }
            }
        }
        return out;
    }

    /** What the Translator follows when translating `from` → `to`. */
    @Transactional(readOnly = true)
    public List<GlossaryEntry> termsFor(String from, String to) {
        return applicable(from, to).stream().map(ApplicableTerm::entry).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void apply(
            Glossary glossary, GlossaryRequest request, String actingUsername, boolean requireEnabledTarget) {
        glossary.setTargetLanguage(languageService.resolve(request.getTargetLanguage(), requireEnabledTarget));
        String source = request.getSourceLanguage() == null
                        || request.getSourceLanguage().isBlank()
                ? null
                : languageService.resolve(request.getSourceLanguage(), false);
        if (source != null && source.equalsIgnoreCase(glossary.getTargetLanguage())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The source and target languages must differ");
        }
        glossary.setSourceLanguage(source);
        glossary.setDescription(
                request.getDescription() == null || request.getDescription().isBlank()
                        ? null
                        : request.getDescription().strip());
        glossary.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        glossary.setUpdatedBy(actingUsername);
    }

    private void replaceTerms(Glossary glossary, List<TermDto> terms) {
        List<GlossaryTerm> rows = new ArrayList<>(terms.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < terms.size(); i++) {
            TermDto t = terms.get(i);
            String source = t.getSource().strip().replaceAll("\\s+", " ");
            if (!seen.add(source.toLowerCase(Locale.ROOT))) {
                throw new AppException(
                        HttpStatus.BAD_REQUEST, "Term " + (i + 1) + ": \"" + source + "\" is listed twice");
            }
            String target = t.isDoNotTranslate()
                    ? source
                    : t.getTarget() == null ? "" : t.getTarget().strip().replaceAll("\\s+", " ");
            if (target.isEmpty()) {
                throw new AppException(
                        HttpStatus.BAD_REQUEST,
                        "Term " + (i + 1) + ": give \"" + source
                                + "\" a translation, or mark it as \"don't translate\"");
            }
            rows.add(GlossaryTerm.builder()
                    .glossaryId(glossary.getId())
                    .position(i)
                    .source(source)
                    .target(target)
                    .doNotTranslate(t.isDoNotTranslate())
                    .caseSensitive(t.isCaseSensitive())
                    .note(
                            t.getNote() == null || t.getNote().isBlank()
                                    ? null
                                    : t.getNote().strip())
                    .build());
        }
        termRepository.deleteByGlossaryId(glossary.getId());
        termRepository.saveAll(rows);
        glossary.setTermCount(rows.size());
    }

    private Glossary find(Long id) {
        return glossaryRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Glossary not found with id: " + id));
    }

    private static TermDto toDto(GlossaryTerm t) {
        TermDto dto = new TermDto();
        dto.setId(t.getId());
        dto.setSource(t.getSource());
        dto.setTarget(t.getTarget());
        dto.setDoNotTranslate(t.isDoNotTranslate());
        dto.setCaseSensitive(t.isCaseSensitive());
        dto.setNote(t.getNote());
        return dto;
    }

    private static GlossaryResponse toResponse(Glossary g, List<TermDto> terms) {
        return new GlossaryResponse(
                g.getId(),
                g.getName(),
                g.getSourceLanguage(),
                g.getTargetLanguage(),
                g.getDescription(),
                g.isEnabled(),
                g.getTermCount(),
                g.getCreatedBy(),
                g.getUpdatedBy(),
                g.getCreatedAt(),
                g.getUpdatedAt(),
                terms);
    }
}
