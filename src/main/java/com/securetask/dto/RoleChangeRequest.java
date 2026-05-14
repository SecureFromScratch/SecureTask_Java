package com.securetask.dto;

import com.securetask.entity.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for changing a user's role.
 * Only accepts 'role' — the client cannot set id, username, email, or passwordHash.
 */
public class RoleChangeRequest {

    @NotNull(message = "Role is required")
    private Role role;

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
