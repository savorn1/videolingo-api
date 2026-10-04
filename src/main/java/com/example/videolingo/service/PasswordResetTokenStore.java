package com.example.videolingo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Password-reset tokens live in Redis with a TTL, so expiry needs no cleanup
// job. Only a SHA-256 hash of the token is stored — a Redis dump alone can't
// be replayed as a reset link. Each user has at most one live token: issuing a
// new one drops the previous, so only the most recent email's link works.
@Component
@RequiredArgsConstructor
public class PasswordResetTokenStore {

    private static final String TOKEN_KEY = "pwreset:token:";
    private static final String USER_KEY = "pwreset:user:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    @Value("${app.password-reset.ttl-minutes}")
    private long ttlMinutes;

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    /** Issues a fresh token for the user, invalidating any earlier one. Returns the raw token for the email link. */
    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);
        Duration ttl = Duration.ofMinutes(ttlMinutes);

        String previous = redis.opsForValue().get(USER_KEY + userId);
        if (previous != null) {
            redis.delete(TOKEN_KEY + previous);
        }
        redis.opsForValue().set(TOKEN_KEY + hash, userId.toString(), ttl);
        redis.opsForValue().set(USER_KEY + userId, hash, ttl);
        return token;
    }

    /** Single-use: returns the token's user and deletes it, or empty if unknown/expired. */
    public Optional<Long> consume(String token) {
        String hash = hash(token);
        String userId = redis.opsForValue().getAndDelete(TOKEN_KEY + hash);
        if (userId == null) {
            return Optional.empty();
        }
        redis.delete(USER_KEY + userId);
        return Optional.of(Long.valueOf(userId));
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
