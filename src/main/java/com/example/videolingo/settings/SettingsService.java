package com.example.videolingo.settings;

import com.example.videolingo.ai.AiProperties;
import com.example.videolingo.entity.AppSetting;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AppSettingRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.subtitle.SubtitleRules;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Runtime-editable settings. Each section is one JSON row in app_settings,
// merged over built-in defaults (so a field added later still gets its
// default). Values are cached in memory and reloaded after every change —
// this service assumes a single backend instance.
@Service
@RequiredArgsConstructor
public class SettingsService {

    public enum Section {
        GENERAL(Settings.General.class), VIDEO(Settings.Video.class), TRANSLATION(Settings.Translation.class),
        AI(Settings.Ai.class), STORAGE(Settings.Storage.class);

        final Class<?> type;

        Section(Class<?> type) {
            this.type = type;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Section of(String key) {
            for (Section s : values()) {
                if (s.key().equals(key)) {
                    return s;
                }
            }
            throw new AppException(HttpStatus.NOT_FOUND, "Unknown settings section '" + key + "'");
        }
    }

    /** A section as the admin UI sees it. */
    public record SectionView(String section, Object values, Object defaults, boolean customized, Long version, String updatedBy,
                              LocalDateTime updatedAt,
                              // Read-only facts about the server's configuration (never secrets).
                              Map<String, Object> info) {
    }

    private final AppSettingRepository repository;
    private final AiProperties aiProperties;
    private final LanguageService languageService;
    private final Validator validator;
    private final ObjectMapper jackson;

    @Value("${spring.servlet.multipart.max-file-size:5MB}")
    private DataSize multipartLimit;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${s3.bucket:}")
    private String bucket;

    @Value("${aws.region:}")
    private String region;

    @Value("${s3.endpoint:}")
    private String s3Endpoint;

    @Value("${s3.public-endpoint:}")
    private String s3PublicEndpoint;

    private final Map<Section, Object> cache = new ConcurrentHashMap<>();

    // ── typed reads (used across the app) ────────────────────────────────

    public Settings.General general() {
        return (Settings.General) current(Section.GENERAL);
    }

    public Settings.Video video() {
        return (Settings.Video) current(Section.VIDEO);
    }

    public Settings.Translation translation() {
        return (Settings.Translation) current(Section.TRANSLATION);
    }

    public Settings.Ai ai() {
        return (Settings.Ai) current(Section.AI);
    }

    public Settings.Storage storage() {
        return (Settings.Storage) current(Section.STORAGE);
    }

    /** Default subtitle rules for a new track in {@code language}. */
    public SubtitleRules subtitleDefaults(String language) {
        Settings.Translation t = translation();
        String primary = language == null ? "" : language.split("-")[0].toLowerCase(Locale.ROOT);
        Settings.SubtitleRulesSetting r = t.compactLanguages().contains(primary) ? t.compactRules() : t.standardRules();
        return new SubtitleRules(r.maxCharsPerLine(), r.maxLines(), r.minDurationMs(), r.maxDurationMs(), r.maxCps());
    }

    /** Base URL for links sent to people (password reset, {{appUrl}}). */
    public String publicUrl() {
        String custom = general().publicUrl();
        return (custom == null || custom.isBlank() ? frontendUrl : custom).replaceAll("/+$", "");
    }

    public long multipartLimitMb() {
        return Math.max(1, multipartLimit.toMegabytes());
    }

    // ── defaults ─────────────────────────────────────────────────────────

    Object defaults(Section section) {
        return switch (section) {
            case GENERAL -> new Settings.General("VideoLingo", "", "");
            case VIDEO -> new Settings.Video(30, 10, false, 30, 2048);
            case TRANSLATION -> new Settings.Translation(List.of(), rules(SubtitleRules.DEFAULT), rules(SubtitleRules.CJK), List.of("ja", "zh"), false, false);
            case AI -> new Settings.Ai(true, true, true, true, true, true, true,
                    aiProperties.model(), aiProperties.fallbackModel() == null ? "" : aiProperties.fallbackModel(),
                    aiProperties.generationEffort(), aiProperties.chatEffort(),
                    aiProperties.monthlyBudgetUsd(), aiProperties.budgetEnforced(), aiProperties.maxChatMessageChars(), 6, 5, 8);
            case STORAGE -> new Settings.Storage((int) multipartLimitMb(), List.of(), (int) Math.min(5, multipartLimitMb()), null);
        };
    }

    private static Settings.SubtitleRulesSetting rules(SubtitleRules r) {
        return new Settings.SubtitleRulesSetting(r.maxCharsPerLine(), r.maxLines(), r.minDurationMs(), r.maxDurationMs(), r.maxCps());
    }

    // ── admin API ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SectionView> all() {
        List<SectionView> out = new ArrayList<>();
        for (Section s : Section.values()) {
            out.add(view(s));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SectionView view(Section section) {
        AppSetting row = repository.findById(section.key()).orElse(null);
        return new SectionView(section.key(), current(section), defaults(section), row != null,
                row == null ? null : row.getVersion(), row == null ? null : row.getUpdatedBy(), row == null ? null : row.getUpdatedAt(), info(section));
    }

    /**
     * Replaces a section. {@code expectedVersion} (from the view the admin
     * edited; -1 when it was on defaults) guards against overwriting someone
     * else's change; null skips the check.
     */
    @Transactional
    public SectionView update(Section section, Object values, Long expectedVersion, String actor) {
        Object normalized = check(section, values);
        AppSetting row = repository.findById(section.key()).orElse(null);
        // expectedVersion: null = don't check; -1 = "it was on defaults when I read it".
        if (expectedVersion != null) {
            boolean stale = row == null ? expectedVersion != -1 : !Objects.equals(row.getVersion(), expectedVersion);
            if (stale) {
                String who = row == null ? "someone (restored to defaults)" : row.getUpdatedBy() == null ? "someone else" : row.getUpdatedBy();
                throw new AppException(HttpStatus.CONFLICT, "These settings were changed by " + who + " since you opened them. Reload and re-apply your changes.");
            }
        }
        if (row == null) {
            row = AppSetting.builder().section(section.key()).build();
        }
        try {
            row.setValueJson(jackson.writeValueAsString(normalized));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise settings", e);
        }
        row.setUpdatedBy(actor);
        repository.saveAndFlush(row);
        cache.remove(section);
        return view(section);
    }

    /** Back to built-in defaults (the stored row is removed). */
    @Transactional
    public SectionView reset(Section section) {
        repository.deleteById(section.key());
        repository.flush();
        cache.remove(section);
        return view(section);
    }

    // ── internals ─────────────────────────────────────────────────────────

    private Object current(Section section) {
        return cache.computeIfAbsent(section, this::load);
    }

    private Object load(Section section) {
        Object defaults = defaults(section);
        AppSetting row = repository.findById(section.key()).orElse(null);
        if (row == null) {
            return defaults;
        }
        try {
            ObjectNode merged = jackson.valueToTree(defaults);
            merged.setAll((ObjectNode) jackson.readTree(row.getValueJson()));
            return jackson.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).treeToValue(merged, section.type);
        } catch (Exception e) {
            // A corrupt row must not take the app down — fall back to defaults.
            return defaults;
        }
    }

    /** Bean Validation plus the checks that need other data; returns the value to store. */
    private Object check(Section section, Object values) {
        if (values == null || !section.type.isInstance(values)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Invalid " + section.key() + " settings");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(values);
        if (!violations.isEmpty()) {
            Map<String, String> errors = violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .collect(Collectors.toMap(v -> v.getPropertyPath().toString().replace(".<list element>", "").replaceAll("durationOrdered$", "maxDurationMs"),
                            ConstraintViolation::getMessage, (a, b) -> a, LinkedHashMap::new));
            throw new SettingsValidationException(errors);
        }
        if (values instanceof Settings.General g) {
            return new Settings.General(g.siteName().strip(), blankToEmpty(g.supportEmail()), blankToEmpty(g.publicUrl()).replaceAll("/+$", ""));
        }
        if (values instanceof Settings.Translation t) {
            List<String> targets = new ArrayList<>(new LinkedHashSet<>(t.defaultTargetLanguages().stream()
                    .map(code -> languageService.resolve(code, true)).toList()));
            List<String> compact = new ArrayList<>(new LinkedHashSet<>(t.compactLanguages()));
            return new Settings.Translation(targets, t.standardRules(), t.compactRules(), compact, t.blockPublishWithIssues(), t.requireApprovalToPublish());
        }
        if (values instanceof Settings.Ai a) {
            if (a.budgetEnforced() && a.monthlyBudgetUsd() == null) {
                throw new SettingsValidationException(Map.of("monthlyBudgetUsd", "set a budget to enforce it"));
            }
            if (a.model().equals(blankToEmpty(a.fallbackModel()))) {
                throw new SettingsValidationException(Map.of("fallbackModel", "must differ from the main model"));
            }
            return a;
        }
        if (values instanceof Settings.Storage st) {
            long limit = multipartLimitMb();
            Map<String, String> errors = new LinkedHashMap<>();
            if (st.maxUploadMb() > limit) {
                errors.put("maxUploadMb", "the server accepts at most " + limit + " MB per request (spring.servlet.multipart.max-file-size)");
            }
            if (st.maxSubtitleUploadMb() > limit) {
                errors.put("maxSubtitleUploadMb", "the server accepts at most " + limit + " MB per request (spring.servlet.multipart.max-file-size)");
            }
            if (!errors.isEmpty()) {
                throw new SettingsValidationException(errors);
            }
            return new Settings.Storage(st.maxUploadMb(), new ArrayList<>(new LinkedHashSet<>(st.allowedUploadTypes())), st.maxSubtitleUploadMb(), st.storageQuotaGb());
        }
        return values;
    }

    private Map<String, Object> info(Section section) {
        Map<String, Object> info = new LinkedHashMap<>();
        switch (section) {
            case GENERAL -> {
                info.put("serverFrontendUrl", frontendUrl);
                info.put("emailConfigured", !mailHost.isBlank());
            }
            case AI -> {
                info.put("apiKeyConfigured", aiProperties.configured());
                info.put("pricedModels", aiProperties.pricing() == null ? List.of() : List.copyOf(aiProperties.pricing().keySet()));
            }
            case STORAGE -> {
                info.put("bucket", bucket);
                info.put("region", region);
                info.put("endpoint", s3Endpoint.isBlank() ? "AWS S3" : s3Endpoint);
                info.put("publicEndpoint", s3PublicEndpoint);
                info.put("configured", !bucket.isBlank());
                info.put("multipartLimitMb", multipartLimitMb());
            }
            default -> {
            }
        }
        return info;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.strip();
    }
}
