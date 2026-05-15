package com.securetask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetask.dto.RegisterRequest;
import com.securetask.dto.TaskCreateRequest;
import com.securetask.repository.AttachmentRepository;
import com.securetask.repository.TaskRepository;
import com.securetask.repository.UserRepository;
import com.securetask.service.FileStorageService;
import com.securetask.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestSecretsManagerConfig.class)
class AttachmentControllerTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper objectMapper;

    // Replace both storage beans so no disk or S3 access occurs in tests.
    @MockBean(name = "localFileStorageService")
    private FileStorageService localStorageService;

    @MockBean(name = "s3FileStorageService")
    private FileStorageService s3StorageService;

    private static final byte[] PNG_BYTES = {
        (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @BeforeEach
    void setup() {
        attachmentRepository.deleteAll();
        taskRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(reg("alice", "alice@example.com", "password123"));
        userService.register(reg("bob", "bob@example.com", "password456"));

        // load() returns a stream by default — used by download tests
        Mockito.when(localStorageService.load(anyString()))
               .thenReturn(new ByteArrayInputStream(PNG_BYTES));
    }

    private long createTask(String username, String title) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                .with(user(username).roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq(title))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    // ── POST /api/tasks/{taskId}/attachments ─────────────────────────────────

    @Test
    void uploadAttachment_validPng_returns201() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.originalFilename").value("photo.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void uploadAttachment_unauthenticated_returns401() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadAttachment_anotherUserTask_returns404() throws Exception {
        long bobTaskId = createTask("bob", "Bob's task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/tasks/" + bobTaskId + "/attachments")
                .file(file)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadAttachment_disallowedType_returns422() throws Exception {
        long taskId = createTask("alice", "My task");
        byte[] htmlBytes = "<html>bad</html>".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "evil.jpg", "image/jpeg", htmlBytes);

        mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(containsString("File type not allowed")));
    }

    @Test
    void uploadAttachment_emptyFile_returns422() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void attachmentResponse_containsNoStorageKey() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
    }

    // ── GET /api/tasks/{taskId}/attachments ──────────────────────────────────

    @Test
    void listAttachments_returnsOnlyTaskAttachments() throws Exception {
        long aliceTaskId = createTask("alice", "Alice task");
        long bobTaskId = createTask("bob", "Bob task");

        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);
        // Alice uploads 2 attachments to her task
        mockMvc.perform(multipart("/api/tasks/" + aliceTaskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf())).andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/tasks/" + aliceTaskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf())).andExpect(status().isCreated());
        // Bob uploads 1 to his task
        mockMvc.perform(multipart("/api/tasks/" + bobTaskId + "/attachments")
                .file(file).with(user("bob").roles("VIEWER")).with(csrf())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/tasks/" + aliceTaskId + "/attachments")
                .with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ── GET /api/tasks/{taskId}/attachments/{id} ─────────────────────────────

    @Test
    void downloadAttachment_ownAttachment_returns200WithDisposition() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String uploadBody = mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        long attachId = objectMapper.readTree(uploadBody).get("id").asLong();

        mockMvc.perform(get("/api/tasks/" + taskId + "/attachments/" + attachId)
                .with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("photo.png")));
    }

    @Test
    void downloadAttachment_anotherUserAttachment_returns404() throws Exception {
        long aliceTaskId = createTask("alice", "Alice task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String uploadBody = mockMvc.perform(multipart("/api/tasks/" + aliceTaskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        long attachId = objectMapper.readTree(uploadBody).get("id").asLong();

        // Bob tries to download Alice's attachment via Alice's task ID
        mockMvc.perform(get("/api/tasks/" + aliceTaskId + "/attachments/" + attachId)
                .with(user("bob").roles("VIEWER")))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/tasks/{taskId}/attachments/{id} ──────────────────────────

    @Test
    void deleteAttachment_ownAttachment_returns204() throws Exception {
        long taskId = createTask("alice", "My task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String uploadBody = mockMvc.perform(multipart("/api/tasks/" + taskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        long attachId = objectMapper.readTree(uploadBody).get("id").asLong();

        mockMvc.perform(delete("/api/tasks/" + taskId + "/attachments/" + attachId)
                .with(user("alice").roles("VIEWER")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAttachment_anotherUserAttachment_returns404() throws Exception {
        long aliceTaskId = createTask("alice", "Alice task");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String uploadBody = mockMvc.perform(multipart("/api/tasks/" + aliceTaskId + "/attachments")
                .file(file).with(user("alice").roles("VIEWER")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        long attachId = objectMapper.readTree(uploadBody).get("id").asLong();

        mockMvc.perform(delete("/api/tasks/" + aliceTaskId + "/attachments/" + attachId)
                .with(user("bob").roles("VIEWER")).with(csrf()))
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

    private static TaskCreateRequest createReq(String title) {
        TaskCreateRequest r = new TaskCreateRequest();
        r.setTitle(title);
        return r;
    }
}
