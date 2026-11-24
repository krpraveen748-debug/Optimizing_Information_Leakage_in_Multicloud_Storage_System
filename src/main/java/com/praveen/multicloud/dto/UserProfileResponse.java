package com.praveen.multicloud.dto;

import java.time.LocalDateTime;

public record UserProfileResponse(
        Long id,
        String username,
        String fullName,
        String email,
        String role,
        LocalDateTime createdAt) {
}
