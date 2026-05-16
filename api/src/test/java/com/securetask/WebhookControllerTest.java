package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.UserResponse;
import com.securetask.dto.WebhookRequest;
import com.securetask.repository.UserRepository;
import com.securetask.repository.WebhookRepository;
import com.securetask.service.OutboundHttpClient;
import com.securetask.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Override the allowlist for tests:
//  - 93.184.216.34: example.com's public IP — used by the happy-path test (no DNS needed)
//  - 127.0.0.1, 10.0.0.1, 169.254.169.254: must be in the allowlist so the blocked-IP
//    tests reach the IP-range defence-in-depth check and produce the "internal addresses"
//    error (not the earlier "not on allowlist" error).
@TestPropertySource(properties = "ssrf.allowed-domains=93.184.216.34,127.0.0.1,10.0.0.1,169.254.169.254")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestSecretsManagerConfig.class)
class WebhookControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    // Replaced by a Mockito mock so tests do not make real outbound HTTP calls.
    @MockBean
    private OutboundHttpClient outboundHttpClient;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setup() {
        webhookRepository.deleteAll();
        userRepository.deleteAll();
        UserResponse alice = userService.register(reg("alice", "alice@example.com", "password123"));
        aliceId = alice.getId();
        UserResponse bob = userService.register(reg("bob", "bob@example.com", "password456"));
        bobId = bob.getId();
    }

    // ── POST /api/webhooks ───────────────────────────────────────────────────

    @Test
    void registerWebhook_authenticated_returnsCreated() throws Exception {
        mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://example.com/hook"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.url").value("https://example.com/hook"))
                .andExpect(jsonPath("$.userId").isNumber());
    }

    @Test
    void registerWebhook_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/webhooks")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://example.com/hook"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerWebhook_responseContainsNoPasswordHash() throws Exception {
        mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://example.com/hook"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // ── GET /api/webhooks ────────────────────────────────────────────────────

    @Test
    void listWebhooks_returnsOnlyOwnWebhooks() throws Exception {
        // Alice registers two webhooks, Bob registers one.
        mockMvc.perform(post("/api/webhooks").with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://alice.example.com/hook1"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/webhooks").with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://alice.example.com/hook2"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/webhooks").with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://bob.example.com/hook"))))
                .andExpect(status().isCreated());

        // Alice should see only her 2 webhooks.
        mockMvc.perform(get("/api/webhooks").with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].url", everyItem(containsString("alice.example.com"))));
    }

    @Test
    void listWebhooks_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/webhooks"))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/webhooks/{id} ─────────────────────────────────────────────

    @Test
    void deleteOwnWebhook_returnsNoContent() throws Exception {
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://example.com/hook"))))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/webhooks/" + id)
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAnotherUsersWebhook_returns404() throws Exception {
        // Bob registers a webhook; Alice tries to delete it.
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://bob.example.com/hook"))))
                .andReturn().getResponse().getContentAsString();

        long bobWebhookId = objectMapper.readTree(body).get("id").asLong();

        // Returns 404 rather than 403 so Alice cannot enumerate Bob's webhook IDs.
        mockMvc.perform(delete("/api/webhooks/" + bobWebhookId)
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/webhooks/{id}/test ─────────────────────────────────────────

    @Test
    void testWebhook_validUrl_returns200() throws Exception {
        // 93.184.216.34 is example.com's IP — a public address that passes the SsrfGuard.
        // Using a literal IP avoids DNS resolution in the test environment.
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://93.184.216.34/"))))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        // The SsrfGuard runs for real; only the actual HTTP call is mocked.
        when(outboundHttpClient.post(any(), any())).thenReturn(200);

        mockMvc.perform(post("/api/webhooks/" + id + "/test")
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetStatus").value(200))
                .andExpect(jsonPath("$.message").value("Ping delivered"));
    }

    @Test
    void testWebhook_blockedLoopback_returns422() throws Exception {
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://127.0.0.1:8080/internal"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/webhooks/" + id + "/test")
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("internal")));
    }

    @Test
    void testWebhook_blockedPrivateIp_returns422() throws Exception {
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://10.0.0.1/internal"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/webhooks/" + id + "/test")
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("internal")));
    }

    @Test
    void testWebhook_blockedAwsMetadata_returns422() throws Exception {
        String body = mockMvc.perform(post("/api/webhooks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req("https://169.254.169.254/latest/meta-data/"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/webhooks/" + id + "/test")
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("internal")));
    }

    @Test
    void testWebhook_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/webhooks/1/test").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testWebhook_nonExistentId_returns404() throws Exception {
        mockMvc.perform(post("/api/webhooks/99999/test")
                .with(user("alice").roles("VIEWER"))
                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static RegisterRequest reg(String username, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    private static WebhookRequest req(String url) {
        WebhookRequest r = new WebhookRequest();
        r.setUrl(url);
        return r;
    }
}
