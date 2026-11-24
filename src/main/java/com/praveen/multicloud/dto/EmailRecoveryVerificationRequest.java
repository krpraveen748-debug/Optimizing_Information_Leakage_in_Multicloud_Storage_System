package com.praveen.multicloud.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRecoveryVerificationRequest(
        @NotBlank(message = "Recovery verification is required.")
        @Size(max = 64, message = "Recovery verification is invalid.")
        String challengeToken,
        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        @Size(max = 255, message = "Email is too long.")
        String email,
        @NotBlank(message = "Password is required.")
        @Size(max = 72, message = "Password is too long.")
        String password) {
}
