package com.securetask.controller;

import com.securetask.dto.WebhookRequest;
import com.securetask.dto.WebhookResponse;
import com.securetask.dto.WebhookTestResult;
import com.securetask.service.BlockedUrlException;
import com.securetask.service.ResourceNotFoundException;
import com.securetask.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Registers a new webhook URL for the authenticated user.
     * The URL is stored as-is; SSRF validation happens only when the webhook is fired.
     * @PreAuthorize here is defence-in-depth alongside the service-level check.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<WebhookResponse> register(
            @Valid @RequestBody WebhookRequest request,
            Authentication authentication) {
        WebhookResponse response = webhookService.register(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists webhooks owned by the authenticated user.
     * @PreAuthorize here is defence-in-depth alongside the service-level check.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<WebhookResponse>> listOwn(Authentication authentication) {
        return ResponseEntity.ok(webhookService.listOwn(authentication.getName()));
    }

    /**
     * Deletes a webhook owned by the authenticated user.
     * Returns 404 for both "not found" and "owned by someone else" to prevent enumeration.
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        try {
            webhookService.delete(id, authentication.getName());
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fires a test ping to the registered webhook URL.
     * The service validates the URL against the SSRF guard before making any network call.
     * A blocked URL returns 422 Unprocessable Entity.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/test")
    public ResponseEntity<?> testWebhook(@PathVariable Long id, Authentication authentication) {
        try {
            WebhookTestResult result = webhookService.testWebhook(id, authentication.getName());
            return ResponseEntity.ok(result);
        } catch (BlockedUrlException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", e.getMessage()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}
