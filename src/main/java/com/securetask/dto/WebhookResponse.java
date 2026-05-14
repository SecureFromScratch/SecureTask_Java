package com.securetask.dto;

import com.securetask.entity.Webhook;

import java.time.Instant;

public class WebhookResponse {

    private Long id;
    private String url;
    private Long userId;
    private Instant createdAt;

    public static WebhookResponse from(Webhook webhook) {
        WebhookResponse r = new WebhookResponse();
        r.id = webhook.getId();
        r.url = webhook.getUrl();
        r.userId = webhook.getUser().getId();
        r.createdAt = webhook.getCreatedAt();
        return r;
    }

    public Long getId() { return id; }
    public String getUrl() { return url; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
