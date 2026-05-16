package com.securetask.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

@Component
public class SsrfGuard {

    private final List<String> allowedDomains;

    public SsrfGuard(@Value("${ssrf.allowed-domains}") List<String> allowedDomains) {
        this.allowedDomains = allowedDomains.stream()
                .map(String::toLowerCase)
                .toList();
    }

    public void validate(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new BlockedUrlException("Invalid URL format");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BlockedUrlException("Only https URLs are permitted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedUrlException("URL must contain a host");
        }

        // Allowlist is the primary gate: reject anything not on the list.
        if (!isAllowed(host)) {
            throw new BlockedUrlException("Host is not on the webhook allowlist");
        }

        // Resolve the hostname to an IP and reject internal ranges as defence-in-depth.
        // This catches cases where an allowlisted domain resolves to a private address
        // (e.g. misconfigured internal DNS, or a compromised entry in the allowlist).
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new BlockedUrlException("Could not resolve host: " + host);
        }

        if (address.isLoopbackAddress()   // 127.x.x.x, ::1
         || address.isSiteLocalAddress()  // 10.x, 172.16-31.x, 192.168.x
         || address.isLinkLocalAddress()  // 169.254.x.x — AWS metadata, Docker gateway
         || address.isAnyLocalAddress()) {// 0.0.0.0
            throw new BlockedUrlException("Requests to internal addresses are not permitted");
        }
    }

    // Suffix match: "discord.com" allows "discord.com" and "webhooks.discord.com".
    private boolean isAllowed(String host) {
        String h = host.toLowerCase();
        for (String domain : allowedDomains) {
            if (h.equals(domain) || h.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }
}
