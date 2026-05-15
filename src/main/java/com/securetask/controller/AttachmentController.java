package com.securetask.controller;

import com.securetask.dto.AttachmentResponse;
import com.securetask.service.AttachmentDownload;
import com.securetask.service.AttachmentService;
import com.securetask.service.ResourceNotFoundException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks/{taskId}/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable Long taskId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        try {
            AttachmentResponse response = attachmentService.upload(taskId, file, authentication.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable Long taskId,
            Authentication authentication) {
        try {
            List<AttachmentResponse> attachments = attachmentService.listByTask(taskId, authentication.getName());
            return ResponseEntity.ok(attachments);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    // Always sets Content-Disposition: attachment so browsers download the file
    // rather than rendering it inline. Without this, a stored HTML or SVG file
    // could execute scripts in the user's browser context.
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{attachmentId}")
    public ResponseEntity<?> download(
            @PathVariable Long taskId,
            @PathVariable Long attachmentId,
            Authentication authentication) {
        try {
            AttachmentDownload dl = attachmentService.download(taskId, attachmentId, authentication.getName());
            String safeFilename = dl.originalFilename()
                    .replace("\"", "").replace("\n", "").replace("\r", "");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(dl.contentType()))
                    .contentLength(dl.fileSize())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + safeFilename + "\"")
                    .body(new InputStreamResource(dl.inputStream()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<?> delete(
            @PathVariable Long taskId,
            @PathVariable Long attachmentId,
            Authentication authentication) {
        try {
            attachmentService.delete(taskId, attachmentId, authentication.getName());
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}
