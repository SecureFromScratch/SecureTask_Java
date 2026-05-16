package com.securetask.controller;

import com.securetask.dto.TaskCreateRequest;
import com.securetask.dto.TaskPinRequest;
import com.securetask.dto.TaskResponse;
import com.securetask.dto.TaskUpdateRequest;
import com.securetask.service.ResourceNotFoundException;
import com.securetask.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<TaskResponse> create(
            @Valid @RequestBody TaskCreateRequest request,
            Authentication authentication) {
        TaskResponse response = taskService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<TaskResponse>> listOwn(Authentication authentication) {
        return ResponseEntity.ok(taskService.listOwn(authentication.getName()));
    }

    // PUT replaces the full task content (title, description, status).
    // Returns 404 for both "not found" and "owned by another user" to prevent ID enumeration.
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody TaskUpdateRequest request,
            Authentication authentication) {
        try {
            TaskResponse response = taskService.update(id, request, authentication.getName());
            return ResponseEntity.ok(response);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/pin")
    public ResponseEntity<?> pin(@PathVariable Long id, @RequestBody TaskPinRequest request) {
        try {
            return ResponseEntity.ok(taskService.pin(id, request.isPinned()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        try {
            taskService.delete(id, authentication.getName());
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}
