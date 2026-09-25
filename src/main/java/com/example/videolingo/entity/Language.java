package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A language the platform supports, referenced by code from Video.language and
// Transcript.language (plain strings, not FKs — so a language that's in use
// can't be deleted or have its code changed; see LanguageServiceImpl).
//
// Invariants (enforced in LanguageServiceImpl, not the schema):
//   - exactly one language is the default, and it is enabled;
//   - codes are unique case-insensitively and stored canonically ("pt-BR").
@Entity
@Table(name = "languages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Language {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // BCP 47 tag: ISO 639 language, optionally with script/region ("zh-Hant", "pt-BR").
    @Column(nullable = false, unique = true, length = 10)
    private String code;

    // English name, e.g. "Japanese".
    @Column(nullable = false, length = 100)
    private String name;

    // Name in the language itself, e.g. "日本語".
    @Column(name = "native_name", length = 100)
    private String nativeName;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
