package com.praveen.multicloud.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import com.praveen.multicloud.repository.UserRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:auth-test-schema.sql"
})
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearUsers() {
        jdbc.update("DELETE FROM file_chunks");
        jdbc.update("DELETE FROM activity_logs");
        jdbc.update("DELETE FROM files");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void mainWorkflowHasRequiredOrderAndOnlyTwoUserNavigationPages() throws Exception {
        String page = mvc.perform(get("/main.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int upload = page.indexOf("<h2>File Upload</h2>");
        int processing = page.indexOf("<h2>File Processing</h2>");
        int similarity = page.indexOf("<h2>Similarity Analysis</h2>");
        int placement = page.indexOf("<h2>Storage Placement</h2>");
        int results = page.indexOf("<h2>Final Results</h2>");
        assertTrue(upload >= 0 && upload < processing && processing < similarity
                && similarity < placement && placement < results);
        assertTrue(page.contains("href=\"/activity.html\">My Activity</a>"));
        assertFalse(page.contains("Recent files"));
        assertFalse(page.contains("href=\"/placement.html\""));

        mvc.perform(get("/activity.html")).andExpect(status().isOk());
        mvc.perform(get("/dashboard.html")).andExpect(status().isOk());
        mvc.perform(get("/placement.html")).andExpect(status().isOk());
        String login = mvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(login.contains("id=\"email\" name=\"email\" type=\"email\""));
        assertTrue(login.contains("id=\"password-visibility-toggle\""));
        assertTrue(login.contains("aria-controls=\"password\" aria-label=\"Show password\" aria-pressed=\"false\""));
        assertTrue(login.contains("href=\"/forgot-email.html\""));
        assertTrue(login.contains("href=\"/forgot-password.html\""));
        String registerPage = mvc.perform(get("/register.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(registerPage.contains("id=\"register-password-visibility-toggle\""));
        assertTrue(registerPage.contains("minlength=\"12\""));
        mvc.perform(get("/forgot-email.html")).andExpect(status().isOk());
        mvc.perform(get("/forgot-password.html")).andExpect(status().isOk());
        String resetPage = mvc.perform(get("/reset-password.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(resetPage.contains("id=\"reset-password-form\" hidden"));
        assertFalse(resetPage.contains("name=\"password\""));
    }

    @Test
    void registrationHashesPasswordAndRejectsDuplicateEmail() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(session);

        mvc.perform(post("/api/auth/register")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("User One", "person@example.test", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("person@example.test"))
                .andExpect(jsonPath("$.username").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist());

        String storedPassword = users.findByEmailIgnoreCase("person@example.test").orElseThrow().getPassword();
        assertNotEquals(PASSWORD, storedPassword);
        assertTrue(passwordEncoder.matches(PASSWORD, storedPassword));

        mvc.perform(post("/api/auth/register")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Another Name", "PERSON@example.test", PASSWORD)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/auth/register")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Bad Input", "not-an-email", "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginAcceptsCorrectPasswordAndRejectsIncorrectPassword() throws Exception {
        register("login@example.test", PASSWORD);
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(session);

        mvc.perform(loginRequest(session, csrf, "login@example.test", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.test"))
                .andExpect(jsonPath("$.password").doesNotExist());

        MockHttpSession failedSession = new MockHttpSession();
        mvc.perform(loginRequest(failedSession, csrf(failedSession),
                        "login@example.test", "incorrect password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password."));
    }

    @Test
    void profileRequiresAuthenticationAndCanBeUpdatedWithoutChangingPassword() throws Exception {
        register("profile@example.test", PASSWORD);
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());

        MockHttpSession session = authenticatedSession("profile@example.test", PASSWORD);
        mvc.perform(get("/api/auth/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist());

        mvc.perform(put("/api/auth/profile")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Updated Name","email":"updated@example.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Updated Name"))
                .andExpect(jsonPath("$.email").value("updated@example.test"));

        assertTrue(passwordEncoder.matches(PASSWORD,
                users.findByEmailIgnoreCase("updated@example.test").orElseThrow().getPassword()));
    }

    @Test
    void userCannotAccessAdminRouteButAdminCan() throws Exception {
        register("user@example.test", PASSWORD);
        MockHttpSession userSession = authenticatedSession("user@example.test", PASSWORD);
        mvc.perform(get("/api/admin/users").session(userSession))
                .andExpect(status().isForbidden());

        users.saveAndFlush(newAdmin());
        MockHttpSession adminSession = authenticatedSession("admin@example.test", PASSWORD);
        mvc.perform(get("/api/admin/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].role").value(containsInAnyOrder("USER", "ADMIN")));
    }

    @Test
    void usersCannotViewAnotherAccountsFile() throws Exception {
        register("owner@example.test", PASSWORD);
        long ownerId = users.findByEmailIgnoreCase("owner@example.test").orElseThrow().getId();
        jdbc.update("""
                INSERT INTO files (
                    user_id, original_filename, stored_filename, file_size,
                    content_type, total_chunks, upload_status
                ) VALUES (?, 'private.txt', 'private.txt', 1, 'text/plain', 0, 'UPLOADED')
                """, ownerId);
        long fileId = jdbc.queryForObject("SELECT MAX(id) FROM files", Long.class);

        register("other@example.test", PASSWORD);
        MockHttpSession otherSession = authenticatedSession("other@example.test", PASSWORD);
        mvc.perform(get("/api/files/{id}", fileId).session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/files/{id}/download", fileId).session(otherSession))
                .andExpect(status().isNotFound());

        MockHttpSession ownerSession = authenticatedSession("owner@example.test", PASSWORD);
        mvc.perform(get("/api/files/{id}", fileId).session(ownerSession))
                .andExpect(status().isOk());
    }

    @Test
    void adminDashboardAndReportsUseEmptyDatabaseAndAdminAuthorization() throws Exception {
        register("regular@example.test", PASSWORD);
        users.saveAndFlush(newAdmin());
        MockHttpSession adminSession = authenticatedSession("admin@example.test", PASSWORD);

        mvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(2))
                .andExpect(jsonPath("$.totalFiles").value(0))
                .andExpect(jsonPath("$.totalUniqueChunks").value(0))
                .andExpect(jsonPath("$.deduplicatedChunks").value(0));

        mvc.perform(get("/api/admin/reports/storage.csv").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("storage-usage-report.csv")));

        MockHttpSession userSession =
                authenticatedSession("regular@example.test", PASSWORD);
        mvc.perform(get("/api/admin/dashboard").session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUserSearchAndSafeStatusManagement() throws Exception {
        register("managed@example.test", PASSWORD);
        users.saveAndFlush(newAdmin());
        MockHttpSession adminSession = authenticatedSession("admin@example.test", PASSWORD);
        long managedId = users.findByEmailIgnoreCase("managed@example.test").orElseThrow().getId();

        mvc.perform(get("/api/admin/users/search")
                        .param("query", "managed")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("managed@example.test"))
                .andExpect(jsonPath("$[0].password").doesNotExist());

        mvc.perform(patch("/api/admin/users/{id}/status", managedId)
                        .param("value", "DISABLED")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(patch("/api/admin/users/{id}/status",
                        users.findByEmailIgnoreCase("admin@example.test").orElseThrow().getId())
                        .param("value", "DISABLED")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        register("logout@example.test", PASSWORD);
        MockHttpSession session = authenticatedSession("logout@example.test", PASSWORD);

        mvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out."));

        assertTrue(session.isInvalid());
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
    }

    private void register(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/register")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Test User", email, password)))
                .andExpect(status().isCreated());
    }

    private MockHttpSession authenticatedSession(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(loginRequest(session, csrf(session), email, password))
                .andExpect(status().isOk());
        return session;
    }

    private String csrf(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("token").asText();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            MockHttpSession session, String csrf, String email, String password) {
        return post("/api/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email)
                .param("password", password);
    }

    private static String registerJson(String fullName, String email, String password) {
        return """
                {"fullName":"%s","email":"%s","password":"%s"}
                """.formatted(fullName, email, password);
    }

    private UserEntity newAdmin() {
        UserEntity admin = new UserEntity();
        admin.setUsername("admin-user");
        admin.setFullName("Administrator");
        admin.setEmail("admin@example.test");
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        return admin;
    }
}
