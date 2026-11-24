package com.praveen.multicloud.security;

import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public final class AuthenticatedUser implements UserDetails {
    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final UserRole role;
    private final UserStatus status;

    private AuthenticatedUser(UserEntity user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPassword();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.status = user.getStatus();
    }

    public static AuthenticatedUser from(UserEntity user) {
        return new AuthenticatedUser(user);
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public UserRole getRole() { return role; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isEnabled() { return status == UserStatus.ACTIVE; }
}
