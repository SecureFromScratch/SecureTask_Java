package com.securetask.service;

import com.securetask.dto.WebhookRequest;
import com.securetask.dto.WebhookResponse;
import com.securetask.dto.WebhookTestResult;
import com.securetask.entity.User;
import com.securetask.entity.Webhook;
import com.securetask.repository.UserRepository;
import com.securetask.repository.WebhookRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final UserRepository userRepository;
    private final SsrfGuard ssrfGuard;
    private final OutboundHttpClient outboundHttpClient;

    public WebhookService(WebhookRepository webhookRepository,
                          UserRepository userRepository,
                          SsrfGuard ssrfGuard,
                          OutboundHttpClient outboundHttpClient) {
        this.webhookRepository = webhookRepository;
        this.userRepository = userRepository;
        this.ssrfGuard = ssrfGuard;
        this.outboundHttpClient = outboundHttpClient;
    }

    /**
     * Registers a webhook URL for the authenticated user.
     * URL validation intentionally happens only at fire time so that the distinction
     * between "storing a URL" and "the server making an outbound request to it" is clear.
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public WebhookResponse register(WebhookRequest request, String callerUsername) {
        User caller = resolveUser(callerUsername);
        Webhook webhook = new Webhook();
        webhook.setUser(caller);
        webhook.setUrl(request.getUrl());
        return WebhookResponse.from(webhookRepository.save(webhook));
    }

    /**
     * Returns only the webhooks that belong to the calling user.
     * Using findByUser prevents horizontal privilege escalation — callers never see
     * webhooks registered by other accounts.
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<WebhookResponse> listOwn(String callerUsername) {
        User caller = resolveUser(callerUsername);
        return webhookRepository.findByUser(caller)
                .stream()
                .map(WebhookResponse::from)
                .toList();
    }

    /**
     * Deletes a webhook owned by the caller.
     * findByIdAndUser returns empty for both "not found" and "belongs to someone else",
     * so a non-owner cannot distinguish a missing webhook from one owned by another user.
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public void delete(Long id, String callerUsername) {
        User caller = resolveUser(callerUsername);
        Webhook webhook = webhookRepository.findByIdAndUser(id, caller)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook not found"));
        webhookRepository.delete(webhook);
    }

    /**
     * Fires a test ping to the registered URL.
     *
     * The SsrfGuard validates the URL before the HTTP call is made. Removing or
     * bypassing that call is the vulnerable pattern this lab demonstrates — an
     * attacker could reach internal services, cloud metadata endpoints, or scan
     * the internal network by controlling the stored URL.
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public WebhookTestResult testWebhook(Long id, String callerUsername) {
        User caller = resolveUser(callerUsername);
        Webhook webhook = webhookRepository.findByIdAndUser(id, caller)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook not found"));

        // SSRF guard: rejects internal addresses before the HTTP client is invoked.
        ssrfGuard.validate(webhook.getUrl());

        int status = outboundHttpClient.post(webhook.getUrl(), Map.of("event", "test"));
        return new WebhookTestResult(status, "Ping delivered");
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
