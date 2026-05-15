package com.securetask.dto;

import com.securetask.entity.Attachment;

import java.time.Instant;

public class AttachmentResponse {

    private Long id;
    private String originalFilename;
    private String contentType;
    private long fileSize;
    private Instant uploadedAt;

    // storageKey is intentionally excluded — it is an internal storage detail
    // that must never be exposed to clients.

    public static AttachmentResponse from(Attachment a) {
        AttachmentResponse r = new AttachmentResponse();
        r.id = a.getId();
        r.originalFilename = a.getOriginalFilename();
        r.contentType = a.getContentType();
        r.fileSize = a.getFileSize();
        r.uploadedAt = a.getUploadedAt();
        return r;
    }

    public Long getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public Instant getUploadedAt() { return uploadedAt; }
}
