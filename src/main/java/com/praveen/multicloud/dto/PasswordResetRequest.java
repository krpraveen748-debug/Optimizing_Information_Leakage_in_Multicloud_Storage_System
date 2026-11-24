package com.praveen.multicloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "Reset token is required.")
        @Size(max = 64, message = "Reset token is invalid.")
        String token,
        @NotBlank(message = "New password is required.")
        @Size(min = 12, max = 72, message = "Password must contain 12 to 72 characters.")
        String newPassword,
        @NotBlank(message = "Password confirmation is required.")
        @Size(min = 12, max = 72, message = "Password confirmation must contain 12 to 72 characters.")
        String confirmPassword) {
}
