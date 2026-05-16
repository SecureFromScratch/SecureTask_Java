package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.TokenResponse;
import com.securetask.entity.RefreshToken;
import com.securetask.entity.User;
import com.securetask.repository.RefreshTokenRepository;
import com.securetask.repository.TaskRepository;
import com.securetask.repository.UserRepository;
import com.securetask.service.JwtService;
import com.securetask.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestSecretsManagerConfig.class)
class TokenControllerTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JwtService jwtService;
    @Autowired private UserService userService;

    // TaskRepository needed to delete tasks before users (FK constraint)
    @Autowired(required = false)
    private TaskRepository taskRepository;

    @BeforeEach
    void setup() {
        refreshTokenRepository.deleteAll();
        if (taskRepository != null) taskRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(reg("alice", "alice@example.com", "password123"));
        userService.register(reg("bob",   "bob@example.com",   "password456"));
    }

    // ── POST /api/auth/token ─────────────────────────────────────────────────

    @Test
    void issueToken_validCredentials_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", "alice",
                        "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    @Test
    void issueToken_invalidCredentials_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", "alice",
                        "password", "wrongpassword"))))
                .andExpect(status().isUnauthorized());
    }

    // ── Bearer token auth on API ─────────────────────────────────────────────

    @Test
    void apiRequest_withValidBearer_returns200() throws Exception {
        TokenResponse tokens = getTokenFor("alice", "password123");

        mockMvc.perform(get("/api/tasks")
                .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void apiRequest_withTamperedToken_returns401() throws Exception {
        TokenResponse tokens = getTokenFor("alice", "password123");
        String original = tokens.getAccessToken();

        // Split the token into header.payload.signature
        String[] parts = original.split("\\.");
        // Decode payload, modify it, re-encode — signature will be invalid
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payload = new String(payloadBytes);
        // Change the subject
        String tamperedPayload = payload.replace("\"alice\"", "\"bob\"");
        String encodedTamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayload.getBytes());
        String tamperedToken = parts[0] + "." + encodedTamperedPayload + "." + parts[2];

        mockMvc.perform(get("/api/tasks")
                .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiRequest_withAlgNone_returns401() throws Exception {
        // alg:none token — should be rejected
        String algNoneToken = "eyJhbGciOiJub25lIn0" +
                ".eyJzdWIiOiJhbGljZSIsInJvbGVzIjpbIlJPTEVfVklFV0VSIl0sImlzcyI6InNlY3VyZXRhc2siLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6OTk5OTk5OTk5OX0" +
                ".";

        mockMvc.perform(get("/api/tasks")
                .header("Authorization", "Bearer " + algNoneToken))
                .andExpect(status().isUnauthorized());
    }

    // ── Refresh token rotation ───────────────────────────────────────────────

    @Test
    void refreshToken_valid_returnsNewPair() throws Exception {
        TokenResponse first = getTokenFor("alice", "password123");

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "refreshToken", first.getRefreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.refreshToken").value(not(first.getRefreshToken())));
    }

    @Test
    void refreshToken_reuse_returns401() throws Exception {
        TokenResponse first = getTokenFor("alice", "password123");
        String rt = first.getRefreshToken();

        // First use — should succeed
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rt))))
                .andExpect(status().isOk());

        // Second use of same token — must be rejected (token was rotated)
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rt))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshToken_expired_returns401() throws Exception {
        // Get the user entity and directly insert an expired refresh token into the DB
        User alice = userRepository.findByUsername("alice").orElseThrow();

        RefreshToken expiredToken = new RefreshToken();
        String rawToken = "expired-test-token-value-12345678";
        // Compute SHA-256 manually to match JwtService.sha256()
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes());
        String hexHash = java.util.HexFormat.of().formatHex(hash);
        expiredToken.setTokenHash(hexHash);
        expiredToken.setUser(alice);
        expiredToken.setExpiresAt(Instant.now().minusSeconds(3600)); // already expired
        refreshTokenRepository.save(expiredToken);

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rawToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(containsString("expired")));
    }

    @Test
    void revokeToken_thenRefreshFails() throws Exception {
        TokenResponse tokens = getTokenFor("alice", "password123");
        String rt = tokens.getRefreshToken();

        // Revoke should succeed with 200
        mockMvc.perform(post("/api/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rt))))
                .andExpect(status().isOk());

        // Subsequent refresh attempt must be rejected
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rt))))
                .andExpect(status().isUnauthorized());
    }

    // ── Session auth regression ──────────────────────────────────────────────

    @Test
    void sessionAuth_stillWorks() throws Exception {
        // Existing session-based auth must continue to function after JWT addition
        mockMvc.perform(get("/api/tasks").with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TokenResponse getTokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username,
                        "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    private static RegisterRequest reg(String username, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }
}
