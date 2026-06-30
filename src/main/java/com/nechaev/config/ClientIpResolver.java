package com.nechaev.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;

// Single source of truth for deriving the client IP used as a rate-limit key. Shared by
// PerIpRateLimitFilter (HTTP requests) and the WebSocket handshake (StompRateLimitInterceptor),
// so the X-Forwarded-For / trusted-proxy handling is identical on both transports.
@Component
public class ClientIpResolver {

    // Pure IPv6 textual representation tops out at ~45 chars, but a zone id (e.g.,
    // "fe80::1%eth0") can extend it. Cap is a DoS-defence (don't let a giant header
    // bloat Redis keys); InetAddress.ofLiteral does the actual format validation.
    private static final int MAX_IP_LENGTH = 60;

    // Headroom for surrounding whitespace before .trim() reduces firstHop to a candidate IP.
    private static final int FIRST_HOP_RAW_MAX_LENGTH = MAX_IP_LENGTH + 16;

    private final boolean trustedProxy;

    public ClientIpResolver(AppProperties props) {
        this.trustedProxy = props.protection().perIpRateLimit().trustedProxy();
    }

    /**
     * Resolve the client IP from the X-Forwarded-For header (may be null) and the socket
     * peer address. When trustedProxy is set, the first valid XFF hop wins; otherwise, or
     * on a missing/spoofed/invalid header, the socket peer address is used.
     */
    public String resolve(String xForwardedFor, String remoteAddr) {
        if (trustedProxy && xForwardedFor != null && !xForwardedFor.isBlank()) {
            int comma = xForwardedFor.indexOf(',');
            int rawLen = (comma == -1) ? xForwardedFor.length() : comma;
            // Reject before allocating a substring of an attacker-sized header.
            if (rawLen <= FIRST_HOP_RAW_MAX_LENGTH) {
                String firstHop = (comma == -1 ? xForwardedFor : xForwardedFor.substring(0, comma)).trim();
                if (isValidIp(firstHop)) {
                    return firstHop;
                }
            }
            // Misconfigured proxy or spoofed header — fall back to the socket peer
            // rather than letting an attacker control the rate-limit key.
        }
        return remoteAddr;
    }

    private static boolean isValidIp(String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) return false;
        try {
            InetAddress.ofLiteral(candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
