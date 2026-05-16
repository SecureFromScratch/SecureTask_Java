package com.securetask.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    // Sanitized display name — never used as a storage path.
    @Column(nullable = false, length = 255)
    private String originalFilename;

    // UUID-based storage key — prevents path traversal and filename guessing.
    @Column(nullable = false, length = 255)
    private String storageKey;

    // Detected from magic bytes, not from the client-supplied Content-Type header.
    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    public Long getId() { return id; }

    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public Instant getUploadedAt() { return uploadedAt; }
}
