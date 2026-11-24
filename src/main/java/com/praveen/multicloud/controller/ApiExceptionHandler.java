package com.praveen.multicloud.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.*;
import com.praveen.multicloud.service.FileService;
import com.praveen.multicloud.service.AesGcmEncryptionService;
import com.praveen.multicloud.service.InformationLeakageAnalysisService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.praveen.multicloud.service.EmailAlreadyRegisteredException;
import com.praveen.multicloud.service.AccountRecoveryService;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateEmail(EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(AccountRecoveryService.InvalidRecoveryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRecovery(
            AccountRecoveryService.InvalidRecoveryException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(AccountRecoveryService.RecoveryRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRecoveryRateLimit(
            AccountRecoveryService.RecoveryRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({
            AccountRecoveryService.RecoveryUnavailableException.class,
            AccountRecoveryService.RecoveryDeliveryException.class
    })
    public ResponseEntity<Map<String, Object>> handleRecoveryUnavailable(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "The request conflicts with existing account data."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", "Validation failed.");
        response.put("fields", ex.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new)));
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(FileService.FileTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleFileTooLarge(FileService.FileTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({
            AesGcmEncryptionService.EncryptionConfigurationException.class,
            AesGcmEncryptionService.EncryptionKeyUnavailableException.class
    })
    public ResponseEntity<Map<String, Object>> handleEncryptionUnavailable(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(InformationLeakageAnalysisService.ProviderConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleProviderConfigurationUnavailable(
            InformationLeakageAnalysisService.ProviderConfigurationException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled request error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "The request could not be completed."));
    }
}
