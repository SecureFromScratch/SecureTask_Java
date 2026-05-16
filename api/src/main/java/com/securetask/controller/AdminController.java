package com.securetask.controller;

import com.securetask.dto.RoleChangeRequest;
import com.securetask.dto.UserResponse;
import com.securetask.service.AdminService;
import com.securetask.service.ConflictException;
import com.securetask.service.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Lists all users.
     * @PreAuthorize here is defence-in-depth alongside the service-level check.
     * The service annotation is the primary guard; this one ensures any future
     * refactoring that bypasses the service still fails at the controller.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(adminService.listAllUsers());
    }

    /**
     * Changes a user's role.
     * The caller's username is taken from the authenticated session, not from
     * the request body — the client cannot impersonate a different admin.
     * @PreAuthorize here is defence-in-depth alongside the service-level check.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/users/{id}/role")
    public ResponseEntity<?> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody RoleChangeRequest request,
            Authentication authentication) {
        try {
            UserResponse updated = adminService.changeUserRole(
                    id, request.getRole(), authentication.getName());
            return ResponseEntity.ok(updated);
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
