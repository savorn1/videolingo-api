package com.example.videolingo.repository;

import com.example.videolingo.entity.Language;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LanguageRepository extends JpaRepository<Language, Long>, JpaSpecificationExecutor<Language> {

    Optional<Language> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    Optional<Language> findFirstByIsDefaultTrue();

    List<Language> findAllByOrderByNameAsc();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Language l set l.isDefault = false where l.isDefault = true")
    void clearDefault();
}
