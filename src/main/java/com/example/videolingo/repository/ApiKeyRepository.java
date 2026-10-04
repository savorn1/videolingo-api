package com.example.videolingo.repository;

import com.example.videolingo.entity.ApiKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    Optional<ApiKey> findByKeyHash(String keyHash);
}
