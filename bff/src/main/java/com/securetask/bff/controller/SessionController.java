package com.securetask.bff.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
public class SessionController {

    private final RestTemplate restTemplate;
    private final String apiBaseUrl;

    public SessionController(RestTemplate restTemplate,
                              @Value("${bff.api.base-url}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
    }

    // Called by the browser's existing form login (application/x-www-form-urlencoded).
    // The _csrf parameter in the form body is validated by BFF's own CsrfFilter.
    @PostMapping(value = "/login", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<?> loginForm(@RequestParam String username,
                                       @RequestParam String password,
                                       HttpSession session) {
        return doLogin(Map.of("username", username, "password", password), session);
    }

    // Called by API clients (JSON body) — kept for backwards compatibility.
    @PostMapping("/bff/login")
    public ResponseEntity<?> loginJson(@RequestBody Map<String, String> credentials,
                                       HttpSession session) {
        return doLogin(credentials, session);
    }

    private ResponseEntity<?> doLogin(Map<String, String> credentials, HttpSession session) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            apiBaseUrl + "/api/auth/token", credentials, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid credentials"));
        }

        // Store tokens in the server-side session — never sent to the browser.
        session.setAttribute("accessToken", response.getBody().get("accessToken"));
        session.setAttribute("refreshToken", response.getBody().get("refreshToken"));

        return ResponseEntity.ok(Map.of("message", "Login successful"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        return doLogout(session);
    }

    @PostMapping("/bff/logout")
    public ResponseEntity<?> logoutBff(HttpSession session) {
        return doLogout(session);
    }

    private ResponseEntity<?> doLogout(HttpSession session) {
        String refreshToken = (String) session.getAttribute("refreshToken");
        if (refreshToken != null) {
            try {
                restTemplate.postForEntity(
                    apiBaseUrl + "/api/auth/revoke",
                    Map.of("refreshToken", refreshToken),
                    Void.class);
            } catch (Exception ignored) {}
        }
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
