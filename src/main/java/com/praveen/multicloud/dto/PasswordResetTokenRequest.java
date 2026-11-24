package com.praveen.multicloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetTokenRequest(
        @NotBlank(message = "Reset token is required.")
        @Size(max = 64, message = "Reset token is invalid.")
        String token) {
}
