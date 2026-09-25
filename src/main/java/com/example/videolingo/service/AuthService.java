package com.example.videolingo.service;

import com.example.videolingo.dto.AuthResponse;
import com.example.videolingo.dto.ForgotPasswordRequest;
import com.example.videolingo.dto.LoginRequest;
import com.example.videolingo.dto.LogoutRequest;
import com.example.videolingo.dto.RefreshRequest;
import com.example.videolingo.dto.ResetPasswordWithTokenRequest;

public interface AuthService {

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshRequest request);

    void logout(LogoutRequest request);

    // Always succeeds silently, whether or not the email matches an account,
    // so the endpoint can't be used to discover registered addresses.
    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordWithTokenRequest request);
}
