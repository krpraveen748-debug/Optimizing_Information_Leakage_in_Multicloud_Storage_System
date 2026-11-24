package com.praveen.multicloud.service;

import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserStatus;
import com.praveen.multicloud.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class AccountRecoveryService {
    private static final String EMAIL_RECOVERY = "EMAIL_RECOVERY";
    private static final String PASSWORD_RESET = "PASSWORD_RESET";
    private static final int MAX_REQUESTS_PER_HOUR = 3;

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final RecoveryEmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String recoveryBaseUrl;
    private final int tokenTtlMinutes;

    public AccountRecoveryService(
            UserRepository users,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            RecoveryEmailSender emailSender,
            @Value("${app.recovery.base-url:http://localhost:8080}") String recoveryBaseUrl,
            @Value("${app.recovery.token-ttl-minutes:15}") int tokenTtlMinutes) {
        if (tokenTtlMinutes < 1 || tokenTtlMinutes > 60) {
            throw new IllegalArgumentException("Recovery token lifetime must be between 1 and 60 minutes.");
        }
        this.users = users;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.recoveryBaseUrl = recoveryBaseUrl.replaceAll("/+$", "");
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    @Transactional
    public String beginEmailRecovery(String username) {
        UserEntity user = users.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(InvalidRecoveryException::new);
        enforceRequestLimit(user.getId(), EMAIL_RECOVERY);
        String token = newToken();
        storeToken(user.getId(), EMAIL_RECOVERY, token);
        return token;
    }

    @Transactional(noRollbackFor = InvalidRecoveryException.class)
    public String verifyEmailRecovery(String token, String email, String password) {
        RecoveryToken challenge = findValidToken(token, EMAIL_RECOVERY);
        if (challenge == null) {
            throw new InvalidRecoveryException("Recovery step expired. Start again.");
        }
        consumeToken(challenge.id());

        UserEntity user = users.findById(challenge.userId())
                .orElseThrow(InvalidRecoveryException::new);
        boolean emailMatches = user.getEmail().equalsIgnoreCase(email.trim());
        boolean passwordMatches = passwordEncoder.matches(password, user.getPassword());
        if (!emailMatches || !passwordMatches) {
            throw new InvalidRecoveryException(
                    "The registered email and password could not be verified. Start again.");
        }
        return user.getEmail();
    }

    @Transactional
    public void requestPasswordReset(String username) {
        if (!emailSender.isConfigured()) {
            throw new RecoveryUnavailableException();
        }
        Optional<UserEntity> account = users.findByUsernameIgnoreCase(username.trim());
        if (account.isEmpty() || account.get().getStatus() != UserStatus.ACTIVE) {
            return;
        }

        UserEntity user = account.get();
        if (requestCount(user.getId(), PASSWORD_RESET) >= MAX_REQUESTS_PER_HOUR) {
            return;
        }

        String token = newToken();
        storeToken(user.getId(), PASSWORD_RESET, token);
        String resetLink = recoveryBaseUrl + "/reset-password.html?token=" + token;
        try {
            emailSender.sendPasswordReset(user.getEmail(), resetLink, tokenTtlMinutes);
        } catch (MailException exception) {
            throw new RecoveryDeliveryException();
        }
    }

    @Transactional(readOnly = true)
    public boolean isPasswordResetTokenValid(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM account_recovery_tokens
                 WHERE token_hash = ? AND purpose = ?
                   AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                """, Integer.class, hashToken(token), PASSWORD_RESET);
        return count != null && count > 0;
    }

    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new InvalidRecoveryException("New password and confirmation do not match.");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRecoveryException("Password must not exceed 72 UTF-8 bytes.");
        }

        RecoveryToken recoveryToken = findValidToken(token, PASSWORD_RESET);
        if (recoveryToken == null) {
            throw new InvalidRecoveryException("Reset link is invalid, expired, or already used.");
        }
        UserEntity user = users.findById(recoveryToken.userId())
                .filter(account -> account.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidRecoveryException::new);
        user.setPassword(passwordEncoder.encode(newPassword));
        users.saveAndFlush(user);
        jdbc.update("""
                UPDATE account_recovery_tokens
                   SET consumed_at = CURRENT_TIMESTAMP(6)
                 WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL
                """, user.getId(), PASSWORD_RESET);
    }

    private void enforceRequestLimit(long userId, String purpose) {
        if (requestCount(userId, purpose) >= MAX_REQUESTS_PER_HOUR) {
            throw new RecoveryRateLimitException();
        }
    }

    private int requestCount(long userId, String purpose) {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM account_recovery_tokens
                 WHERE user_id = ? AND purpose = ? AND created_at >= ?
                """, Integer.class, userId, purpose, Timestamp.valueOf(since));
        return count == null ? 0 : count;
    }

    private void storeToken(long userId, String purpose, String rawToken) {
        jdbc.update("DELETE FROM account_recovery_tokens WHERE expires_at <= CURRENT_TIMESTAMP(6)");
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(tokenTtlMinutes);
        jdbc.update("""
                INSERT INTO account_recovery_tokens
                    (user_id, purpose, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, userId, purpose, hashToken(rawToken), Timestamp.valueOf(expiresAt));
    }

    private RecoveryToken findValidToken(String rawToken, String purpose) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        List<RecoveryToken> tokens = jdbc.query("""
                SELECT id, user_id
                  FROM account_recovery_tokens
                 WHERE token_hash = ? AND purpose = ?
                   AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                 FOR UPDATE
                """, (row, index) -> new RecoveryToken(row.getLong("id"), row.getLong("user_id")),
                hashToken(rawToken), purpose);
        return tokens.isEmpty() ? null : tokens.getFirst();
    }

    private void consumeToken(long id) {
        jdbc.update("""
                UPDATE account_recovery_tokens
                   SET consumed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ? AND consumed_at IS NULL
                """, id);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static final class InvalidRecoveryException extends RuntimeException {
        public InvalidRecoveryException() {
            this("The recovery details could not be verified.");
        }

        public InvalidRecoveryException(String message) {
            super(message);
        }
    }

    public static final class RecoveryRateLimitException extends RuntimeException {
        public RecoveryRateLimitException() {
            super("Too many recovery attempts. Please try again later.");
        }
    }

    public static final class RecoveryUnavailableException extends RuntimeException {
        public RecoveryUnavailableException() {
            super("Password recovery email is not configured. Contact the administrator.");
        }
    }

    public static final class RecoveryDeliveryException extends RuntimeException {
        public RecoveryDeliveryException() {
            super("The reset email could not be sent. Please try again later.");
        }
    }

    private record RecoveryToken(long id, long userId) {}
}
