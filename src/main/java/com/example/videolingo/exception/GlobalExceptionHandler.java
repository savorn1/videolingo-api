package com.example.videolingo.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;
import java.util.stream.Collectors;

// Deliberately no catch-all Exception handler — every thrown exception must be
// an AppException (or one of the other handled types below) so its response
// body matches this envelope; a ResponseStatusException instead falls through
// to Spring's default handling, which dispatches to /error and returns the raw
// {timestamp, status, error, path} whitelabel body instead of this shape.
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    ResponseEntity<Map<String, Object>> handleAppException(AppException ex) {
        log.warn("status={} message={}", ex.getStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("statusCode", ex.getStatus().value(), "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        log.warn("status=400 message=Validation failed errors={}", errors);
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "Validation failed", "errors", errors));
    }

    // A missing query param or multipart part (e.g. an upload with no file)
    // otherwise falls through to Spring's default body, which has no message.
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<Map<String, Object>> handleMissingInput(Exception ex) {
        String name = ex instanceof MissingServletRequestPartException part ? part.getRequestPartName()
                : ((MissingServletRequestParameterException) ex).getParameterName();
        log.warn("status=400 message=Missing request input '{}'", name);
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "Missing required field: " + name, "errors", Map.of(name, "is required")));
    }

    // Malformed JSON or a value that doesn't fit the field (e.g. an unknown enum
    // constant) — otherwise the whitelabel body, with no message.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("status=400 message=Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "The request body is malformed or has an invalid value"));
    }

    // A query/path value of the wrong type, e.g. ?from=yesterday for a date or
    // ?status=NOPE for an enum.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("status=400 message=Invalid value for '{}': {}", ex.getName(), ex.getValue());
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "Invalid value for '" + ex.getName() + "'", "errors", Map.of(ex.getName(), "is invalid")));
    }

    @ExceptionHandler(com.example.videolingo.settings.SettingsValidationException.class)
    ResponseEntity<Map<String, Object>> handleSettingsValidation(com.example.videolingo.settings.SettingsValidationException ex) {
        log.warn("status=400 message=Settings validation failed errors={}", ex.getErrors());
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "Validation failed", "errors", ex.getErrors()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("statusCode", 403, "message", "Access denied"));
    }

    // Covers MaxUploadSizeExceededException too (it extends MultipartException) —
    // without this, an oversized file falls through to Spring's whitelabel /error
    // handling instead of this envelope, same reasoning as the no-catch-all note above.
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        log.warn("status=400 message=Multipart request error: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("statusCode", 400, "message", "Invalid file upload — check the file size and try again."));
    }
}
