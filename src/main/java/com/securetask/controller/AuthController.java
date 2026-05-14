package com.securetask.controller;

import com.securetask.dto.RegisterRequest;
import com.securetask.dto.UserResponse;
import com.securetask.service.ConflictException;
import com.securetask.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Registers a new user.
     * Accepts a DTO, not a JPA entity, to prevent mass-assignment attacks.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            UserResponse user = userService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Returns the currently authenticated user's safe profile.
     * @PreAuthorize is defence-in-depth alongside the filter-chain's anyRequest().authenticated().
     * If the filter rule is ever accidentally relaxed, the method-level check still holds.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        UserResponse user = userService.findByUsername(authentication.getName());
        return ResponseEntity.ok(user);
    }
}
