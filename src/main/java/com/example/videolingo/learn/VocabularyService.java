package com.example.videolingo.learn;

import com.example.videolingo.ai.AiClientService;
import com.example.videolingo.dto.GlossaryDtos.ApplicableTerm;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiGeneration;
import com.example.videolingo.entity.StudyCard;
import com.example.videolingo.entity.WordLookup;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.glossary.GlossaryService;
import com.example.videolingo.repository.AiGenerationRepository;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.StudyCardRepository;
import com.example.videolingo.repository.WordLookupRepository;
import com.example.videolingo.service.impl.TranscriptServiceImpl;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Words looked up from subtitles, and the learner's flashcards.
//   Lookup: the glossary first (exact, and free), then the shared cache, and
//   only then the AI — so each word costs at most one call, ever.
//   Cards: saved words and AI key points, reviewed with spaced repetition (Srs).
@Service
@RequiredArgsConstructor
public class VocabularyService {

    static final int MAX_WORD_LENGTH = 60;

    public record Lookup(String word, String language, String targetLanguage, String translation, String meaning, String partOfSpeech,
                         String example, String source) {
    }

    // What the AI answers with (structured output).
    public record LookupOutput(
            @JsonPropertyDescription("The word's most likely meaning here, translated into the target language — a word or short phrase.")
            String translation,
            @JsonPropertyDescription("A one-sentence explanation of the meaning, in the target language, for a learner.")
            String meaning,
            @JsonPropertyDescription("Part of speech in the target language, e.g. noun, verb; empty if unclear.")
            String partOfSpeech,
            @JsonPropertyDescription("A short, simple example sentence using the word, in the word's own language.")
            String example) {
    }

    public record CardRequest(@NotBlank @Size(max = 300) String front, @NotBlank @Size(max = 1000) String back, @Size(max = 10) String language,
                              @Size(max = 500) String context, Long videoId, Long atMs) {
    }

    public record CardDto(Long id, String front, String back, String language, String context, Long videoId, Long atMs, StudyCard.Source source,
                          double ease, int intervalDays, int repetitions, int lapses, LocalDateTime dueAt, LocalDateTime lastReviewedAt, LocalDateTime createdAt) {
    }

    public record ReviewRequest(@NotNull Srs.Grade grade) {
    }

    public record Stats(long total, long due) {
    }

    private final GlossaryService glossaryService;
    private final WordLookupRepository lookupRepository;
    private final StudyCardRepository cardRepository;
    private final AiGenerationRepository generationRepository;
    private final AiClientService aiClient;
    private final LanguageRepository languageRepository;
    private final LearnService learnService;
    private final ObjectMapper objectMapper;

    // ── Lookup ────────────────────────────────────────────────────────────

    public Lookup lookup(String rawWord, String language, String target, String context, Long videoId, String username) {
        String word = normalizeWord(rawWord);
        if (word.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Pick a word to look up");
        }
        if (language == null || language.isBlank() || target == null || target.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Say which language the word is in and which to translate it into");
        }
        String from = language.strip().toLowerCase(Locale.ROOT);
        String to = target.strip().toLowerCase(Locale.ROOT);

        for (ApplicableTerm t : glossaryService.applicable(from, to)) {
            if (t.source().equalsIgnoreCase(word)) {
                return new Lookup(word, from, to, t.target(), t.note(), null, null, "glossary");
            }
        }
        var cached = lookupRepository.findByWordAndFromLanguageAndToLanguage(word, from, to);
        if (cached.isPresent()) {
            return toLookup(cached.get(), "cache");
        }

        aiClient.requireReady(AiFeature.LOOKUP);
        String fromName = languageName(from);
        String toName = languageName(to);
        List<TextBlockParam> system = List.of(TextBlockParam.builder().text(
                "You are a concise bilingual dictionary for language learners. Explain words from " + fromName + " (" + from
                        + ") in " + toName + " (" + to + "). Give the everyday meaning that fits the context when one is given.").build());
        String prompt = "Word: " + word + (context != null && !context.isBlank() ? "\nContext: " + cap(context.strip(), 300) : "");
        LookupOutput out = aiClient.structured(new AiClientService.Call(AiFeature.LOOKUP, videoId, null, null, username),
                system, prompt, LookupOutput.class, 1024, "low").value();
        WordLookup row = WordLookup.builder()
                .word(word).fromLanguage(from).toLanguage(to)
                .translation(cap(blankTo(out.translation(), "—"), 300))
                .meaning(cap(out.meaning(), 500))
                .partOfSpeech(cap(out.partOfSpeech(), 40))
                .example(cap(out.example(), 300))
                .build();
        try {
            row = lookupRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            // Someone else looked it up at the same moment; theirs is just as good.
        }
        return toLookup(row, "ai");
    }

    /** Trims surrounding punctuation and lower-cases: "Hello," → "hello". */
    static String normalizeWord(String raw) {
        if (raw == null) {
            return "";
        }
        String w = raw.strip().replaceAll("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$", "").toLowerCase(Locale.ROOT);
        return w.length() > MAX_WORD_LENGTH ? w.substring(0, MAX_WORD_LENGTH) : w;
    }

    // ── Cards ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CardDto> cards(Long userId, String search, boolean dueOnly, int page, int size) {
        List<Specification<StudyCard>> c = new ArrayList<>();
        c.add((root, q, cb) -> cb.equal(root.get("userId"), userId));
        if (search != null && !search.isBlank()) {
            String pattern = "%" + TranscriptServiceImpl.escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
            c.add((root, q, cb) -> cb.or(cb.like(root.get("frontKey"), pattern, '\\'), cb.like(cb.lower(root.get("back")), pattern, '\\')));
        }
        if (dueOnly) {
            LocalDateTime now = LocalDateTime.now();
            c.add((root, q, cb) -> cb.lessThanOrEqualTo(root.get("dueAt"), now));
        }
        Page<StudyCard> result = cardRepository.findAll(Specification.allOf(c),
                PageRequest.of(Math.max(page - 1, 0), Math.max(1, Math.min(size, 100)), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.map(VocabularyService::toDto));
    }

    @Transactional(readOnly = true)
    public Stats stats(Long userId) {
        return new Stats(cardRepository.countByUserId(userId), cardRepository.countByUserIdAndDueAtLessThanEqual(userId, LocalDateTime.now()));
    }

    @Transactional
    public CardDto create(Long userId, CardRequest r, StudyCard.Source source) {
        String front = r.front().strip();
        String language = r.language() == null || r.language().isBlank() ? null : r.language().strip().toLowerCase(Locale.ROOT);
        if (cardRepository.existsByUserIdAndFrontKeyAndLanguage(userId, front.toLowerCase(Locale.ROOT), language)) {
            throw new AppException(HttpStatus.CONFLICT, "“" + front + "” is already in your cards");
        }
        return toDto(cardRepository.save(StudyCard.builder()
                .userId(userId)
                .front(front)
                .frontKey(front.toLowerCase(Locale.ROOT))
                .back(r.back().strip())
                .language(language)
                .context(blankTo(cap(r.context(), 500), null))
                .videoId(r.videoId())
                .atMs(r.atMs())
                .source(source)
                .build()));
    }

    @Transactional
    public CardDto update(Long userId, Long id, CardRequest r) {
        StudyCard card = find(userId, id);
        card.setFront(r.front().strip());
        card.setFrontKey(card.getFront().toLowerCase(Locale.ROOT));
        card.setBack(r.back().strip());
        card.setContext(blankTo(cap(r.context(), 500), null));
        return toDto(cardRepository.save(card));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        cardRepository.delete(find(userId, id));
    }

    @Transactional(readOnly = true)
    public List<CardDto> due(Long userId, int limit) {
        return cardRepository.due(userId, LocalDateTime.now(), PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream()
                .map(VocabularyService::toDto).toList();
    }

    @Transactional
    public CardDto review(Long userId, Long id, Srs.Grade grade) {
        StudyCard card = find(userId, id);
        LocalDateTime now = LocalDateTime.now();
        Srs.State next = Srs.review(Srs.of(card), grade, now);
        card.setEase(next.ease());
        card.setIntervalDays(next.intervalDays());
        card.setRepetitions(next.repetitions());
        card.setLapses(next.lapses());
        card.setDueAt(next.dueAt());
        card.setLastReviewedAt(now);
        return toDto(cardRepository.save(card));
    }

    /** Turns a video's AI key points into cards (point → explanation). Returns how many were new. */
    @Transactional
    public int fromKeyPoints(Long userId, Long generationId, boolean isAdmin) {
        AiGeneration g = generationRepository.findById(generationId)
                .filter(x -> x.getType() == AiFeature.KEY_POINTS)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Key points not found"));
        learnService.requireWatchable(g.getVideoId(), userId, isAdmin);
        Map<String, Object> content;
        try {
            content = objectMapper.readValue(g.getContentJson(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new AppException(HttpStatus.CONFLICT, "These key points couldn't be read");
        }
        int added = 0;
        if (content.get("keyPoints") instanceof List<?> points) {
            for (Object o : points) {
                if (!(o instanceof Map<?, ?> p) || !(p.get("point") instanceof String point) || point.isBlank()) {
                    continue;
                }
                String language = g.getOutputLanguage() == null ? null : g.getOutputLanguage().toLowerCase(Locale.ROOT);
                if (cardRepository.existsByUserIdAndFrontKeyAndLanguage(userId, cap(point.strip(), 300).toLowerCase(Locale.ROOT), language)) {
                    continue;
                }
                String explanation = p.get("explanation") instanceof String s && !s.isBlank() ? s : point;
                long atMs = p.get("timestampSeconds") instanceof Number n ? n.longValue() * 1000 : 0;
                create(userId, new CardRequest(cap(point.strip(), 300), cap(explanation.strip(), 1000), language, null, g.getVideoId(), atMs),
                        StudyCard.Source.KEY_POINT);
                added++;
            }
        }
        return added;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private StudyCard find(Long userId, Long id) {
        return cardRepository.findByIdAndUserId(id, userId).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Card not found"));
    }

    private String languageName(String code) {
        return languageRepository.findByCodeIgnoreCase(code).map(l -> l.getName()).orElse(code);
    }

    private static Lookup toLookup(WordLookup w, String source) {
        return new Lookup(w.getWord(), w.getFromLanguage(), w.getToLanguage(), w.getTranslation(), w.getMeaning(), w.getPartOfSpeech(),
                w.getExample(), source);
    }

    private static CardDto toDto(StudyCard c) {
        return new CardDto(c.getId(), c.getFront(), c.getBack(), c.getLanguage(), c.getContext(), c.getVideoId(), c.getAtMs(), c.getSource(),
                c.getEase(), c.getIntervalDays(), c.getRepetitions(), c.getLapses(), c.getDueAt(), c.getLastReviewedAt(), c.getCreatedAt());
    }

    private static String cap(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }
}
