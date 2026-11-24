package com.praveen.multicloud.service;

import com.praveen.multicloud.dto.RegisterRequest;
import com.praveen.multicloud.dto.UpdateProfileRequest;
import com.praveen.multicloud.dto.UserProfileResponse;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import com.praveen.multicloud.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class UserAccountService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserProfileResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        validateBcryptPasswordLength(request.password());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        UserEntity user = new UserEntity();
        user.setUsername("user-" + UUID.randomUUID().toString().replace("-", ""));
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return toProfile(users.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        return toProfile(findUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        UserEntity user = findUser(userId);
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
            throw new EmailAlreadyRegisteredException();
        }
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        return toProfile(users.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public List<UserProfileResponse> listProfiles() {
        return users.findAll().stream().map(UserAccountService::toProfile).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAdminUsers(String query) {
        List<UserEntity> matches = query == null || query.isBlank()
                ? users.findAll()
                : users.search(query.trim());
        return matches.stream().map(this::toAdminView).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAdminUser(long userId) {
        return toAdminView(findUser(userId));
    }

    @Transactional
    public Map<String, Object> setStatus(long actorId, long userId, String status) {
        if (actorId == userId) {
            throw new IllegalArgumentException("An administrator cannot deactivate their own account.");
        }
        UserStatus parsed;
        try {
            String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
            parsed = "INACTIVE".equals(normalized)
                    ? UserStatus.DISABLED
                    : UserStatus.valueOf(normalized);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Status must be ACTIVE or INACTIVE.");
        }
        UserEntity user = findUser(userId);
        user.setStatus(parsed);
        return toAdminView(users.saveAndFlush(user));
    }

    private Map<String, Object> toAdminView(UserEntity user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("fullName", user.getFullName() == null ? user.getUsername() : user.getFullName());
        result.put("email", user.getEmail());
        result.put("role", user.getRole().name());
        result.put("status", user.getStatus().name());
        result.put("createdAt", user.getCreatedAt());
        result.put("updatedAt", user.getUpdatedAt());
        return result;
    }

    private UserEntity findUser(Long id) {
        return users.findById(id).orElseThrow(() -> new IllegalStateException("Authenticated account no longer exists."));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateBcryptPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be no more than 72 UTF-8 bytes.");
        }
    }

    private static UserProfileResponse toProfile(UserEntity user) {
        String fullName = user.getFullName() == null ? user.getUsername() : user.getFullName();
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                fullName,
                user.getEmail(),
                user.getRole().name(),
                user.getCreatedAt());
    }
}
