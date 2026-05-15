package com.securetask.dto;

import com.securetask.entity.User;

import java.time.Instant;

public class ProfileResponse {

    private Long id;
    private String username;
    private String email;
    private String role;
    private Instant createdAt;
    private boolean hasAvatar;
    private String storageBackend;

    public static ProfileResponse from(User u) {
        ProfileResponse r = new ProfileResponse();
        r.id = u.getId();
        r.username = u.getUsername();
        r.email = u.getEmail();
        r.role = u.getRole().name();
        r.createdAt = u.getCreatedAt();
        r.hasAvatar = u.getAvatarKey() != null;
        return r;
    }

    public static ProfileResponse from(User u, String storageBackend) {
        ProfileResponse r = from(u);
        r.storageBackend = storageBackend;
        return r;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isHasAvatar() { return hasAvatar; }
    public String getStorageBackend() { return storageBackend; }
}
