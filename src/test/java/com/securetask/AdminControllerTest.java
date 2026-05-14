package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.RoleChangeRequest;
import com.securetask.dto.UserResponse;
import com.securetask.entity.Role;
import com.securetask.repository.UserRepository;
import com.securetask.service.UserService;
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
class AdminControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long adminId;
    private Long viewerId;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();
        // First user → ADMIN
        UserResponse admin = userService.register(reg("admin", "admin@example.com", "password123"));
        adminId = admin.getId();
        // Second user → VIEWER
        UserResponse viewer = userService.register(reg("viewer", "viewer@example.com", "password456"));
        viewerId = viewer.getId();
    }

    // ── GET /api/admin/users ──────────────────────────────────────────────────

    @Test
    void adminCanListAllUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].username", containsInAnyOrder("admin", "viewer")));
    }

    @Test
    void viewerCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsersResponseContainsNoPasswordHash() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[*].password").doesNotExist());
    }

    // ── PATCH /api/admin/users/{id}/role ─────────────────────────────────────

    @Test
    void adminCanChangeAnotherUsersRole() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest();
        req.setRole(Role.DEVELOPER);

        mockMvc.perform(patch("/api/admin/users/" + viewerId + "/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.username").value("viewer"));
    }

    @Test
    void adminCannotChangeOwnRole() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest();
        req.setRole(Role.VIEWER);

        mockMvc.perform(patch("/api/admin/users/" + adminId + "/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("own role")));
    }

    @Test
    void adminCannotDemoteAnotherAdmin() throws Exception {
        // Register a second admin by promoting the viewer first, then testing demotion.
        // Promote viewer → ADMIN so we have two admins.
        RoleChangeRequest promote = new RoleChangeRequest();
        promote.setRole(Role.ADMIN);
        mockMvc.perform(patch("/api/admin/users/" + viewerId + "/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(promote)))
                .andExpect(status().isOk());

        // Now try to demote the newly promoted admin → should be 409.
        RoleChangeRequest demote = new RoleChangeRequest();
        demote.setRole(Role.VIEWER);
        mockMvc.perform(patch("/api/admin/users/" + viewerId + "/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(demote)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("Cannot change the role of another admin")));
    }

    @Test
    void viewerCannotChangeAnyRole() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest();
        req.setRole(Role.DEVELOPER);

        mockMvc.perform(patch("/api/admin/users/" + viewerId + "/role")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingRoleOfNonExistentUserReturns404() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest();
        req.setRole(Role.DEVELOPER);

        mockMvc.perform(patch("/api/admin/users/99999/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleChangeResponseContainsNoPasswordHash() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest();
        req.setRole(Role.DEVELOPER);

        mockMvc.perform(patch("/api/admin/users/" + viewerId + "/role")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static RegisterRequest reg(String username, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }
}
