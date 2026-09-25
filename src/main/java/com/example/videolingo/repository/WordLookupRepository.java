package com.example.videolingo.repository;

import com.example.videolingo.entity.WordLookup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WordLookupRepository extends JpaRepository<WordLookup, Long> {

    Optional<WordLookup> findByWordAndFromLanguageAndToLanguage(String word, String fromLanguage, String toLanguage);
}
