package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestSecretsManagerConfig.class)
class AuthControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    // ── Lab 01: unauthenticated request to /api/me returns 401 ───────────────

    @Test
    void unauthenticatedUserCannotAccessMe() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── Lab 01: authenticated user can access /api/me ─────────────────────────

    @Test
    void authenticatedUserCanAccessMe() throws Exception {
        // Register first so the user exists in the DB.
        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice");
        req.setEmail("alice@example.com");
        req.setPassword("password123");

        mockMvc.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Now call /api/me with a Spring Security test user principal.
        mockMvc.perform(get("/api/me").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ── Lab 01: password hash is never in the /api/me response ───────────────

    @Test
    void passwordHashNotInMeResponse() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("bob");
        req.setEmail("bob@example.com");
        req.setPassword("password123");

        mockMvc.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/me").with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // ── Lab 01: registration returns 201 and safe fields only ────────────────

    @Test
    void registrationReturns201WithSafeFields() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("carol");
        req.setEmail("carol@example.com");
        req.setPassword("password123");

        mockMvc.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.email").value("carol@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // ── Lab 01: duplicate username returns 409 ────────────────────────────────

    @Test
    void duplicateUsernameReturns409() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("dave");
        req.setEmail("dave@example.com");
        req.setPassword("password123");

        mockMvc.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Same username, different email — should still conflict.
        // The error message is intentionally generic to prevent account enumeration.
        req.setEmail("dave2@example.com");
        mockMvc.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already exists")));
    }
}
