package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One rule in a Glossary. Replaced wholesale on every save (like subtitle
// cues), so `position` is 0..n-1 in the order the admin arranged them.
// doNotTranslate = keep `source` as-is ("VideoLingo"); then `target` is
// stored equal to `source`.
@Entity
@Table(name = "glossary_terms", indexes = @Index(name = "idx_glossary_terms_glossary", columnList = "glossary_id, position"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlossaryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "glossary_id", nullable = false)
    private Long glossaryId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 200)
    private String source;

    @Column(nullable = false, length = 200)
    private String target;

    @Builder.Default
    @Column(name = "do_not_translate", nullable = false)
    private boolean doNotTranslate = false;

    @Builder.Default
    @Column(name = "case_sensitive", nullable = false)
    private boolean caseSensitive = false;

    @Column(length = 300)
    private String note;
}
