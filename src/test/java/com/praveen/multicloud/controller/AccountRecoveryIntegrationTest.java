package com.praveen.multicloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import com.praveen.multicloud.repository.UserRepository;
import com.praveen.multicloud.service.RecoveryEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recovery-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:auth-test-schema.sql"
})
@AutoConfigureMockMvc
class AccountRecoveryIntegrationTest {
    private static final String OLD_PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "a new strong password 2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private RecoveryEmailSender emailSender;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM account_recovery_tokens");
        jdbc.update("DELETE FROM activity_logs");
        jdbc.update("DELETE FROM files");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void emailRecoveryRequiresUsernameThenEmailAndPassword() throws Exception {
        UserEntity user = createUser();
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(session);

        mvc.perform(post("/api/recovery/email/username")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"missing-user"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The recovery details could not be verified."));

        String challenge = mvc.perform(post("/api/recovery/email/username")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"registered-user"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rawToken = objectMapper.readTree(challenge).get("challengeToken").asText();
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM account_recovery_tokens", String.class);
        assertNotEquals(rawToken, storedHash);

        mvc.perform(post("/api/recovery/email/verify")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeToken":"%s","email":"wrong@example.test","password":"%s"}
                                """.formatted(rawToken, OLD_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "The registered email and password could not be verified. Start again."));

        String nextChallenge = mvc.perform(post("/api/recovery/email/username")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"registered-user"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String validToken = objectMapper.readTree(nextChallenge).get("challengeToken").asText();
        mvc.perform(post("/api/recovery/email/verify")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeToken":"%s","email":"OWNER@example.test","password":"%s"}
                                """.formatted(validToken, OLD_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void passwordResetEmailsSingleUseLinkAndReplacesHashedPassword() throws Exception {
        UserEntity user = createUser();
        when(emailSender.isConfigured()).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(session);

        mvc.perform(post("/api/recovery/password/request")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"unknown-user"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "If the username matches an active account, a password reset link has been emailed."));
        verify(emailSender, never()).sendPasswordReset(
                eq(user.getEmail()), org.mockito.ArgumentMatchers.anyString(), anyInt());

        mvc.perform(post("/api/recovery/password/request")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"registered-user"}
                                """))
                .andExpect(status().isAccepted());

        var link = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordReset(eq(user.getEmail()), link.capture(), eq(15));
        String token = URI.create(link.getValue()).getQuery().substring("token=".length());

        mvc.perform(post("/api/recovery/password/validate")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mvc.perform(post("/api/recovery/password/reset")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"%s","confirmPassword":"different strong password"}
                                """.formatted(token, NEW_PASSWORD)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/recovery/password/reset")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"%s","confirmPassword":"%s"}
                                """.formatted(token, NEW_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Password reset successfully. You can now sign in."));

        UserEntity updated = users.findByUsername("registered-user").orElseThrow();
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, updated.getPassword()));
        assertTrue(!passwordEncoder.matches(OLD_PASSWORD, updated.getPassword()));
        mvc.perform(post("/api/recovery/password/validate")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void resetRequestFailsClearlyWhenMailIsNotConfiguredAndCsrfIsRequired() throws Exception {
        createUser();
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/recovery/password/request")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"registered-user"}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/recovery/password/request")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"registered-user"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(
                        "Password recovery email is not configured. Contact the administrator."));
    }

    private UserEntity createUser() {
        UserEntity user = new UserEntity();
        user.setUsername("registered-user");
        user.setFullName("Registered User");
        user.setEmail("owner@example.test");
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return users.saveAndFlush(user);
    }

    private String csrf(MockHttpSession session) throws Exception {
        String response = mvc.perform(get("/api/auth/csrf").session(session))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("token").asText();
    }
}
