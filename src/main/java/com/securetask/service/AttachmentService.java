package com.securetask.service;

import com.securetask.dto.AttachmentResponse;
import com.securetask.entity.Attachment;
import com.securetask.entity.Task;
import com.securetask.entity.User;
import com.securetask.repository.AttachmentRepository;
import com.securetask.repository.TaskRepository;
import com.securetask.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final FileTypeValidator fileTypeValidator;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             TaskRepository taskRepository,
                             UserRepository userRepository,
                             @Qualifier("localFileStorageService") FileStorageService fileStorageService,
                             FileTypeValidator fileTypeValidator) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.fileTypeValidator = fileTypeValidator;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public AttachmentResponse upload(Long taskId, MultipartFile file, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(taskId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (file.isEmpty()) {
            throw new InvalidFileTypeException("File must not be empty");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        // Magic bytes check — rejects files whose content does not match an allowed type.
        // Cannot be bypassed by renaming a .jsp to .jpg or sending a fake Content-Type header.
        String detectedType = fileTypeValidator.validate(bytes);

        // UUID key: the original filename is never used as a storage path.
        // Prevents path traversal attacks like ../../etc/passwd.
        String storageKey = UUID.randomUUID().toString();

        // Strip any path components from the display name, then cap to DB column length.
        String rawName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String safeName = Paths.get(rawName).getFileName().toString();
        if (safeName.length() > 255) {
            safeName = safeName.substring(0, 255);
        }

        fileStorageService.store(new ByteArrayInputStream(bytes), storageKey, detectedType, bytes.length);

        Attachment attachment = new Attachment();
        attachment.setTask(task);
        attachment.setOriginalFilename(safeName);
        attachment.setStorageKey(storageKey);
        attachment.setContentType(detectedType);
        attachment.setFileSize(bytes.length);
        return AttachmentResponse.from(attachmentRepository.save(attachment));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<AttachmentResponse> listByTask(Long taskId, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(taskId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        return attachmentRepository.findByTask(task).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public AttachmentDownload download(Long taskId, Long attachmentId, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(taskId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        Attachment attachment = attachmentRepository.findByIdAndTask(attachmentId, task)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
        return new AttachmentDownload(
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getFileSize(),
                fileStorageService.load(attachment.getStorageKey()));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public void delete(Long taskId, Long attachmentId, String callerUsername) {
        User owner = resolveUser(callerUsername);
        Task task = taskRepository.findByIdAndOwner(taskId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        Attachment attachment = attachmentRepository.findByIdAndTask(attachmentId, task)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
        fileStorageService.delete(attachment.getStorageKey());
        attachmentRepository.delete(attachment);
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
