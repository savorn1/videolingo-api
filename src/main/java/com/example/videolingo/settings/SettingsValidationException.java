package com.example.videolingo.settings;

import lombok.Getter;

import java.util.Map;

// Field errors from a settings update, reported like request validation errors.
@Getter
public class SettingsValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public SettingsValidationException(Map<String, String> errors) {
        super("Validation failed");
        this.errors = errors;
    }
}
