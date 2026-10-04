package com.example.videolingo.repository;

import com.example.videolingo.entity.WordLookup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordLookupRepository extends JpaRepository<WordLookup, Long> {

    Optional<WordLookup> findByWordAndFromLanguageAndToLanguage(String word, String fromLanguage, String toLanguage);
}
