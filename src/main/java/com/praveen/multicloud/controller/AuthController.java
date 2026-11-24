package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.RegisterRequest;
import com.praveen.multicloud.dto.UpdateProfileRequest;
import com.praveen.multicloud.dto.UserProfileResponse;
import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.UserAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountService accounts;

    public AuthController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrfToken(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileResponse register(@Valid @RequestBody RegisterRequest request) {
        return accounts.register(request);
    }

    @GetMapping("/profile")
    public UserProfileResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return accounts.getProfile(user.getId());
    }

    @PutMapping("/profile")
    public UserProfileResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateProfileRequest request) {
        return accounts.updateProfile(user.getId(), request);
    }

    public record CsrfResponse(String headerName, String token) {}
}
