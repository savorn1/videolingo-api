package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Cache of AI word lookups, shared by everyone: each (word, from, to) costs
// one AI call ever. `word` is normalised (VocabularyService.normalizeWord).
@Entity
@Table(
        name = "word_lookups",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_word_lookups",
                        columnNames = {"word", "from_language", "to_language"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WordLookup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String word;

    @Column(name = "from_language", nullable = false, length = 10)
    private String fromLanguage;

    @Column(name = "to_language", nullable = false, length = 10)
    private String toLanguage;

    @Column(nullable = false, length = 300)
    private String translation;

    @Column(length = 500)
    private String meaning;

    @Column(name = "part_of_speech", length = 40)
    private String partOfSpeech;

    @Column(length = 300)
    private String example;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
