package com.securetask.service;

import com.securetask.dto.TaskCreateRequest;
import com.securetask.dto.TaskResponse;
import com.securetask.dto.TaskUpdateRequest;
import com.securetask.entity.Task;
import com.securetask.entity.TaskStatus;
import com.securetask.entity.User;
import com.securetask.repository.TaskRepository;
import com.securetask.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskService(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public TaskResponse create(TaskCreateRequest request, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = new Task();
        task.setOwner(owner);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        // pinned and completedAt are server-managed — never set from request body.
        return TaskResponse.from(taskRepository.save(task));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<TaskResponse> listOwn(String callerUsername) {
        User owner = resolveUser(callerUsername);
        return taskRepository.findByOwnerOrderByPinnedDescCreatedAtDesc(owner)
                .stream().map(TaskResponse::from).toList();
    }

    // findByIdAndOwner returns empty for both "not found" and "belongs to another user",
    // so a non-owner cannot distinguish the two cases from the 404 response.
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public TaskResponse update(Long id, TaskUpdateRequest request, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        // completedAt is server-managed: set when transitioning TO DONE, cleared otherwise.
        if (request.getStatus() == TaskStatus.DONE && task.getStatus() != TaskStatus.DONE) {
            task.setCompletedAt(Instant.now());
        } else if (request.getStatus() != TaskStatus.DONE) {
            task.setCompletedAt(null);
        }
        task.setStatus(request.getStatus());
        task.setUpdatedAt(Instant.now());
        return TaskResponse.from(task);
    }

    // Admin-only: pin or unpin any task regardless of owner.
    // Uses findById (no ownership check) because admins operate across all users.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TaskResponse pin(Long id, boolean pinned) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        task.setPinned(pinned);
        task.setUpdatedAt(Instant.now());
        return TaskResponse.from(taskRepository.save(task));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public void delete(Long id, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        taskRepository.delete(task);
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
