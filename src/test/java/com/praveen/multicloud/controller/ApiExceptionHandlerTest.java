package com.praveen.multicloud.controller;

import com.praveen.multicloud.service.AesGcmEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {
    @Test
    void encryptionUnavailableResponseExplainsMissingConfiguration() {
        String message = "APP_ENCRYPTION_KEY_RING is not configured.";
        ResponseEntity<Map<String, Object>> response = new ApiExceptionHandler()
                .handleEncryptionUnavailable(
                        new AesGcmEncryptionService.EncryptionConfigurationException(message));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(message, response.getBody().get("error"));
    }
}
