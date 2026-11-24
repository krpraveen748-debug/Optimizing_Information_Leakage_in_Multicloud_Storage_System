package com.praveen.multicloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsernameRecoveryRequest(
        @NotBlank(message = "Username is required.")
        @Size(max = 50, message = "Username is invalid.")
        String username) {
}
