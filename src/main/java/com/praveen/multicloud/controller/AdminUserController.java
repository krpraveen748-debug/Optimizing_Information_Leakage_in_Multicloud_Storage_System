package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.UserProfileResponse;
import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.UserAccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final UserAccountService accounts;

    public AdminUserController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<UserProfileResponse> users() {
        return accounts.listProfiles();
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam(defaultValue = "") String query) {
        if (query.length() > 255) {
            throw new IllegalArgumentException("Search query is too long.");
        }
        return accounts.listAdminUsers(query);
    }

    @GetMapping("/{id}")
    public Map<String, Object> details(@PathVariable long id) {
        return accounts.getAdminUser(id);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable long id,
            @RequestParam String value) {
        return accounts.setStatus(actor.getId(), id, value);
    }
}
