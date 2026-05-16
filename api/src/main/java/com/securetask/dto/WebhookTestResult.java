package com.securetask.dto;

public class WebhookTestResult {

    private final int targetStatus;
    private final String message;

    public WebhookTestResult(int targetStatus, String message) {
        this.targetStatus = targetStatus;
        this.message = message;
    }

    public int getTargetStatus() { return targetStatus; }
    public String getMessage() { return message; }
}
