package com.example.videolingo.service.impl;

import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.dto.AuthResponse;
import com.example.videolingo.dto.ForgotPasswordRequest;
import com.example.videolingo.dto.LoginRequest;
import com.example.videolingo.dto.LogoutRequest;
import com.example.videolingo.dto.RefreshRequest;
import com.example.videolingo.dto.ResetPasswordWithTokenRequest;
import com.example.videolingo.entity.RefreshToken;
import com.example.videolingo.entity.User;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.RefreshTokenRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.service.AuthService;
import com.example.videolingo.service.JwtService;
import com.example.videolingo.service.PasswordResetMailer;
import com.example.videolingo.service.PasswordResetTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final PasswordResetMailer passwordResetMailer;
    private final SettingsService settings;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return buildAuthResponse(user);
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken stored = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Refresh token expired or revoked");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (!user.isEnabled()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");
        }

        // Rotate: the presented refresh token is single-use.
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return buildAuthResponse(user);
    }

    @Override
    @Transactional
    public void logout(LogoutRequest request) {
        refreshTokenRepository.findByToken(request.getRefreshToken())
                .ifPresent(stored -> {
                    stored.setRevoked(true);
                    refreshTokenRepository.save(stored);
                });
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail().trim())
                .filter(User::isEnabled)
                .ifPresent(user -> {
                    String token = passwordResetTokenStore.issue(user.getId());
                    String link = settings.publicUrl() + "/reset-password?token="
                            + URLEncoder.encode(token, StandardCharsets.UTF_8);
                    passwordResetMailer.send(user.getEmail(), user.getUsername(), link, passwordResetTokenStore.getTtlMinutes());
                });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordWithTokenRequest request) {
        Long userId = passwordResetTokenStore.consume(request.getToken())
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired"));
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired"));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        // Whoever triggered the reset may be locking out someone with a stolen
        // session — sign every existing session out.
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        String refreshToken = issueRefreshToken(user);
        return AuthResponse.builder()
                .accessToken(token)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpiration())
                .username(user.getUsername())
                .role(user.getRole().name())
                .build();
    }

    private String issueRefreshToken(User user) {
        String token = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(token)
                .userId(user.getId())
                .expiresAt(LocalDateTime.now().plus(Duration.ofMillis(refreshExpiration)))
                .build());
        return token;
    }
}
