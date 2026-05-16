package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.TaskCreateRequest;
import com.securetask.dto.TaskUpdateRequest;
import com.securetask.entity.TaskStatus;
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
class TaskControllerTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        taskRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(reg("alice", "alice@example.com", "password123"));
        userService.register(reg("bob",   "bob@example.com",   "password456"));
    }

    // ── POST /api/tasks ──────────────────────────────────────────────────────

    @Test
    void createTask_authenticated_returnsCreated() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Fix login bug", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Fix login bug"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void createTask_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Fix login bug", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createTask_blankTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("   ", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("title"))));
    }

    @Test
    void createTask_nullTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("title"))));
    }

    @Test
    void createTask_oversizedTitle_returns400() throws Exception {
        String longTitle = "x".repeat(201);
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create(longTitle, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("title"))));
    }

    @Test
    void createTask_oversizedDescription_returns400() throws Exception {
        String longDesc = "d".repeat(2001);
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Valid title", longDesc))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("description"))));
    }

    // XSS payload is stored as plain text and returned as-is by the API.
    // It is only dangerous if the frontend renders it with innerHTML — the safe
    // page uses textContent so the payload is displayed literally, not executed.
    @Test
    void createTask_xssPayloadInTitle_returns201() throws Exception {
        String payload = "<img src=x onerror=\"alert('XSS')\">";
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create(payload, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(payload));
    }

    @Test
    void createTask_responseContainsNoPasswordHash() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Test task", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // ── GET /api/tasks ───────────────────────────────────────────────────────

    @Test
    void listTasks_returnsOnlyOwnTasks() throws Exception {
        // Alice creates 2 tasks, Bob creates 1.
        mockMvc.perform(post("/api/tasks").with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Alice task 1", null)))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/tasks").with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Alice task 2", null)))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/tasks").with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Bob task", null)))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/tasks").with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", everyItem(containsString("Alice"))));
    }

    @Test
    void listTasks_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /api/tasks/{id} ──────────────────────────────────────────────────

    @Test
    void updateTask_ownTask_returns200() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Original", null))))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(put("/api/tasks/" + id)
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update("Updated title", "New desc", TaskStatus.IN_PROGRESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void updateTask_anotherUserTask_returns404() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Bob's task", null))))
                .andReturn().getResponse().getContentAsString();
        long bobTaskId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(put("/api/tasks/" + bobTaskId)
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update("Hijacked", null, TaskStatus.DONE))))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/tasks/{id} ───────────────────────────────────────────────

    @Test
    void deleteTask_ownTask_returns204() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                .with(user("alice").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Delete me", null))))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/tasks/" + id)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteTask_anotherUserTask_returns404() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                .with(user("bob").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create("Bob's task", null))))
                .andReturn().getResponse().getContentAsString();
        long bobTaskId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/tasks/" + bobTaskId)
                .with(user("alice").roles("VIEWER")).with(csrf()))
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

    private static TaskCreateRequest create(String title, String description) {
        TaskCreateRequest r = new TaskCreateRequest();
        r.setTitle(title);
        r.setDescription(description);
        return r;
    }

    private static TaskUpdateRequest update(String title, String description, TaskStatus status) {
        TaskUpdateRequest r = new TaskUpdateRequest();
        r.setTitle(title);
        r.setDescription(description);
        r.setStatus(status);
        return r;
    }
}
