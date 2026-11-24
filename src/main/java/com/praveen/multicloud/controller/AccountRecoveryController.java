package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.EmailRecoveryVerificationRequest;
import com.praveen.multicloud.dto.PasswordResetRequest;
import com.praveen.multicloud.dto.PasswordResetTokenRequest;
import com.praveen.multicloud.dto.UsernameRecoveryRequest;
import com.praveen.multicloud.service.AccountRecoveryService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/recovery")
public class AccountRecoveryController {
    private final AccountRecoveryService recovery;

    public AccountRecoveryController(AccountRecoveryService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/email/username")
    public ResponseEntity<Map<String, Object>> beginEmailRecovery(
            @Valid @RequestBody UsernameRecoveryRequest request) {
        String challengeToken = recovery.beginEmailRecovery(request.username());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("challengeToken", challengeToken));
    }

    @PostMapping("/email/verify")
    public ResponseEntity<Map<String, String>> verifyEmailRecovery(
            @Valid @RequestBody EmailRecoveryVerificationRequest request) {
        String verifiedEmail = recovery.verifyEmailRecovery(
                request.challengeToken(), request.email(), request.password());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("email", verifiedEmail, "message", "Identity verified."));
    }

    @PostMapping("/password/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> requestPasswordReset(
            @Valid @RequestBody UsernameRecoveryRequest request) {
        recovery.requestPasswordReset(request.username());
        return Map.of("message",
                "If the username matches an active account, a password reset link has been emailed.");
    }

    @PostMapping("/password/validate")
    public ResponseEntity<Map<String, Boolean>> validatePasswordReset(
            @Valid @RequestBody PasswordResetTokenRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("valid", recovery.isPasswordResetTokenValid(request.token())));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody PasswordResetRequest request) {
        recovery.resetPassword(request.token(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Password reset successfully. You can now sign in."));
    }
}
