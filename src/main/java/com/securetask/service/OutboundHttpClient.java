package com.securetask.service;

@FunctionalInterface
public interface OutboundHttpClient {
    int post(String url, Object body);
}
