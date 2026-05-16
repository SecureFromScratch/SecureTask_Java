package com.securetask.dto;

import com.securetask.entity.Role;
import com.securetask.entity.User;

import java.time.Instant;

/**
 * Safe outbound representation of a User.
 * passwordHash is intentionally omitted — never expose it via the API.
 */
public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private Role role;
    private Instant createdAt;

    public static UserResponse from(User user) {
        UserResponse r = new UserResponse();
        r.id = user.getId();
        r.username = user.getUsername();
        r.email = user.getEmail();
        r.role = user.getRole();
        r.createdAt = user.getCreatedAt();
        return r;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
