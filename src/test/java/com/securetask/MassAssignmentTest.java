package com.securetask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.TaskCreateRequest;
import com.securetask.entity.Role;
import com.securetask.entity.User;
import com.securetask.repository.TaskRepository;
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
class MassAssignmentTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserService userService;

    @BeforeEach
    void setup() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(reg("alice", "alice@example.com", "password123"));

        // Promote alice to ADMIN for pin tests
        User alice = userRepository.findByUsername("alice").orElseThrow();
        alice.setRole(Role.ADMIN);
        userRepository.save(alice);

        userService.register(reg("bob", "bob@example.com", "password456"));
    }

    // ── POST /api/tasks ───────────────────────────────────────────────────────

    @Test
    void createTask_pinnedInBody_isIgnored() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"pinned\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pinned").value(false));
    }

    @Test
    void createTask_completedAtInBody_isIgnored() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"completedAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    // ── PUT /api/tasks/{id} ───────────────────────────────────────────────────

    @Test
    void updateTask_pinnedInBody_isIgnored() throws Exception {
        long id = createTask("bob", "My task");

        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"status\":\"OPEN\",\"pinned\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false));
    }

    @Test
    void updateTask_completedAtInBody_isIgnored() throws Exception {
        long id = createTask("bob", "My task");

        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"status\":\"OPEN\",\"completedAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    // ── completedAt server-managed ────────────────────────────────────────────

    @Test
    void updateTask_statusToDone_setsCompletedAt() throws Exception {
        long id = createTask("bob", "My task");

        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void updateTask_statusFromDone_clearsCompletedAt() throws Exception {
        long id = createTask("bob", "My task");

        // First transition to DONE
        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        // Then move back to IN_PROGRESS
        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My task\",\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    // ── PATCH /api/tasks/{id}/pin ─────────────────────────────────────────────

    @Test
    void pinTask_asAdmin_returns200AndPinnedTrue() throws Exception {
        long id = createTask("bob", "Bob's task");

        mockMvc.perform(patch("/api/tasks/" + id + "/pin")
                .with(user("alice").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
    }

    @Test
    void pinTask_asViewer_returns403() throws Exception {
        long id = createTask("bob", "Bob's task");

        mockMvc.perform(patch("/api/tasks/" + id + "/pin")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pinTask_nonExistentTask_returns404() throws Exception {
        mockMvc.perform(patch("/api/tasks/99999/pin")
                .with(user("alice").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTasks_pinnedTaskAppearsFirst() throws Exception {
        long firstId  = createTask("bob", "First task");
        long secondId = createTask("bob", "Second task");

        // Pin the first-created task
        mockMvc.perform(patch("/api/tasks/" + firstId + "/pin")
                .with(user("alice").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks").with(user("bob").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(firstId))
                .andExpect(jsonPath("$[0].pinned").value(true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long createTask(String username, String title) throws Exception {
        TaskCreateRequest req = new TaskCreateRequest();
        req.setTitle(title);
        String body = mockMvc.perform(post("/api/tasks")
                .with(user(username).roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private static RegisterRequest reg(String username, String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }
}
