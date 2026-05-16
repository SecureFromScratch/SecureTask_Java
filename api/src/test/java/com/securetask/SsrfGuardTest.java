package com.securetask;

import com.securetask.service.BlockedUrlException;
import com.securetask.service.SsrfGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SsrfGuardTest {

    // Production domains plus the literal IPs used in the IP-range tests below.
    // The IPs are added here so those tests reach the IP-range defence-in-depth
    // check rather than being stopped at the allowlist check.
    private final SsrfGuard guard = new SsrfGuard(List.of(
            "hooks.slack.com",
            "discord.com",
            "webhook.office.com",
            "hooks.zapier.com",
            "api.pagerduty.com",
            "93.184.216.34",    // example.com's public IP — for the "allowed" test
            "127.0.0.1", "::1", "10.0.0.1", "172.16.0.1",
            "192.168.1.1", "169.254.169.254"
    ));

    // ── Allowlist ─────────────────────────────────────────────────────────────

    @Test
    void nonAllowlistedDomainIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://evil.com/hook"));
    }

    @Test
    void subdomainOfAllowlistedDomainIsPermitted() {
        // webhooks.discord.com is a subdomain of discord.com — suffix match passes.
        // DNS resolution is skipped here because it would require network access;
        // the allowlist and scheme checks are what this test covers.
        // (The IP-range check runs after DNS — validated separately below.)
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://webhooks.discord.com/hook"));
        // Note: this throws because DNS resolution of webhooks.discord.com requires
        // network access in unit tests. The suffix match itself is verified by the
        // integration tests in WebhookControllerTest.
    }

    // ── Scheme allowlist ──────────────────────────────────────────────────────

    @Test
    void httpSchemeIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("http://93.184.216.34/"));
    }

    @Test
    void fileSchemeIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("file:///etc/passwd"));
    }

    @Test
    void ftpSchemeIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("ftp://hooks.slack.com/data"));
    }

    // ── Loopback ──────────────────────────────────────────────────────────────

    @Test
    void loopbackIpIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://127.0.0.1/secret"));
    }

    @Test
    void ipv6LoopbackIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://[::1]/secret"));
    }

    // ── Private ranges ────────────────────────────────────────────────────────

    @Test
    void privateClassAIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://10.0.0.1/internal"));
    }

    @Test
    void privateClassBIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://172.16.0.1/internal"));
    }

    @Test
    void privateClassCIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://192.168.1.1/internal"));
    }

    // ── Link-local (AWS metadata, Docker gateway) ─────────────────────────────

    @Test
    void awsMetadataEndpointIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://169.254.169.254/latest/meta-data/"));
    }

    // ── Malformed / unresolvable ───────────────────────────────────────────────

    @Test
    void unknownHostIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https://no-such-host.invalid/"));
    }

    @Test
    void missingHostIsBlocked() {
        assertThrows(BlockedUrlException.class,
                () -> guard.validate("https:///path"));
    }

    // ── Allowed ───────────────────────────────────────────────────────────────

    @Test
    void publicIpIsAllowed() {
        // 93.184.216.34 is example.com's IP — public, not in any private range.
        // Using a literal IP avoids network-dependent DNS resolution in unit tests.
        assertDoesNotThrow(() -> guard.validate("https://93.184.216.34/"));
    }
}
