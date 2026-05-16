package com.securetask.controller;

import com.securetask.dto.RefreshRequest;
import com.securetask.dto.TokenRequest;
import com.securetask.dto.TokenResponse;
import com.securetask.entity.User;
import com.securetask.repository.UserRepository;
import com.securetask.service.JwtService;
import com.securetask.service.ResourceNotFoundException;
import com.securetask.service.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class TokenController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public TokenController(AuthenticationManager authenticationManager,
                           UserRepository userRepository,
                           JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@Valid @RequestBody TokenRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            User user = userRepository.findByUsername(auth.getName())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            return ResponseEntity.ok(jwtService.issueTokenPair(user));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            TokenResponse response = jwtService.validateAndRotateRefresh(request.getRefreshToken());
            return ResponseEntity.ok(response);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@Valid @RequestBody RefreshRequest request) {
        jwtService.revoke(request.getRefreshToken());
        return ResponseEntity.ok().build();
    }
}
