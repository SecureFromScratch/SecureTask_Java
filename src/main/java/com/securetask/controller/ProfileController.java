package com.securetask.controller;

import com.securetask.dto.ProfileResponse;
import com.securetask.service.InvalidFileTypeException;
import com.securetask.service.ProfileService;
import com.securetask.service.ResourceNotFoundException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(profileService.getProfile(auth.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            ProfileResponse profile = profileService.uploadAvatar(file, auth.getName());
            return ResponseEntity.ok(profile);
        } catch (InvalidFileTypeException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Served inline so the browser renders it in <img> tags.
    // Safe to serve inline because magic bytes guarantee it is an image, not HTML/JS.
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/avatar")
    public ResponseEntity<?> getAvatar(Authentication auth) {
        try {
            ProfileService.AvatarDownload dl = profileService.getAvatar(auth.getName());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(dl.contentType()))
                    .cacheControl(CacheControl.noCache())
                    .body(new InputStreamResource(dl.inputStream()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/avatar")
    public ResponseEntity<ProfileResponse> deleteAvatar(Authentication auth) {
        return ResponseEntity.ok(profileService.deleteAvatar(auth.getName()));
    }
}
