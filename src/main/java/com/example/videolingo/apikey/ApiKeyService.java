package com.example.videolingo.apikey;

import com.example.videolingo.entity.ApiKey;
import com.example.videolingo.entity.User;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ApiKeyRepository;
import com.example.videolingo.repository.UserRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name,
            // Null = never expires.
            @Min(1) @Max(3650) Integer expiresInDays) {}

    public record ApiKeyResponse(
            Long id,
            String name,
            String prefix,
            String username,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            String revokedBy,
            boolean active) {}

    // The only time the full key is ever returned.
    public record CreatedKey(ApiKeyResponse key, String secret) {}

    /** Resolved owner of a valid key. */
    public record KeyOwner(Long keyId, User user) {}

    // lastUsedAt is refreshed at most this often, so busy integrations don't write on every call.
    private static final long TOUCH_EVERY_SECONDS = 60;

    private final ApiKeyRepository repository;
    private final UserRepository userRepository;

    /** Admins see every key; everyone else sees their own. */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(String username, boolean isAdmin) {
        Sort newest = Sort.by(Sort.Direction.DESC, "id");
        List<ApiKey> keys = isAdmin
                ? repository.findAll(newest)
                : repository.findAll((root, q, cb) -> cb.equal(root.get("username"), username), newest);
        LocalDateTime now = LocalDateTime.now();
        return keys.stream().map(k -> toResponse(k, now)).toList();
    }

    @Transactional
    public CreatedKey create(CreateRequest request, String username) {
        User owner = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "User not found"));
        String secret = ApiKeys.generate();
        ApiKey key = repository.save(ApiKey.builder()
                .name(request.name().strip())
                .prefix(ApiKeys.visiblePrefix(secret))
                .keyHash(ApiKeys.hash(secret))
                .userId(owner.getId())
                .username(owner.getUsername())
                .expiresAt(
                        request.expiresInDays() == null
                                ? null
                                : LocalDateTime.now().plusDays(request.expiresInDays()))
                .build());
        return new CreatedKey(toResponse(key, LocalDateTime.now()), secret);
    }

    @Transactional
    public ApiKeyResponse revoke(Long id, String actor, boolean isAdmin) {
        ApiKey key =
                repository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "API key not found"));
        if (!isAdmin && !key.getUsername().equals(actor)) {
            throw new AppException(HttpStatus.NOT_FOUND, "API key not found");
        }
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(LocalDateTime.now());
            key.setRevokedBy(actor);
            repository.save(key);
        }
        return toResponse(key, LocalDateTime.now());
    }

    /** The enabled owner of an active key, or empty — the caller answers 401. */
    @Transactional
    public Optional<KeyOwner> authenticate(String rawKey) {
        if (!ApiKeys.looksLikeKey(rawKey)) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        return repository
                .findByKeyHash(ApiKeys.hash(rawKey))
                .filter(k -> k.isActive(now))
                .flatMap(k -> userRepository
                        .findById(k.getUserId())
                        .filter(User::isEnabled)
                        .map(u -> {
                            if (k.getLastUsedAt() == null
                                    || k.getLastUsedAt().isBefore(now.minusSeconds(TOUCH_EVERY_SECONDS))) {
                                k.setLastUsedAt(now);
                                repository.save(k);
                            }
                            return new KeyOwner(k.getId(), u);
                        }));
    }

    private static ApiKeyResponse toResponse(ApiKey k, LocalDateTime now) {
        return new ApiKeyResponse(
                k.getId(),
                k.getName(),
                k.getPrefix(),
                k.getUsername(),
                k.getCreatedAt(),
                k.getLastUsedAt(),
                k.getExpiresAt(),
                k.getRevokedAt(),
                k.getRevokedBy(),
                k.isActive(now));
    }
}
