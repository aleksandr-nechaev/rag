package com.nechaev.config;

import com.nechaev.dto.ErrorResponse;
import com.nechaev.service.RedisRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;

// Per-IP rate-limit filter applied to the expensive paths (RAG entry points).
// Static assets, admin endpoints, and other cheap paths are skipped via shouldNotFilter.
@Component
public class PerIpRateLimitFilter extends OncePerRequestFilter {

    // Pure IPv6 textual representation tops out at ~45 chars, but a zone id (e.g.,
    // "fe80::1%eth0") can extend it. Cap is a DoS-defence (don't let a giant header
    // bloat Redis keys); InetAddress.ofLiteral does the actual format validation.
    private static final int MAX_IP_LENGTH = 60;

    // Headroom for surrounding whitespace before .trim() reduces firstHop to a candidate IP.
    private static final int FIRST_HOP_RAW_MAX_LENGTH = MAX_IP_LENGTH + 16;

    // Aligned with ApiExceptionHandler.handleRateLimitExceeded — clients see the same
    // message for any "rate limit hit" outcome (per-IP filter or downstream Resilience4j).
    private static final String RATE_LIMIT_MESSAGE = "Too many requests, please try again in a few seconds.";

    private final RedisRateLimiter rateLimiter;
    private final boolean trustedProxy;
    private final Counter deniedCounter;
    // Pre-serialized once: the body is constant, no need to run Jackson on every denial.
    private final byte[] rateLimitBodyBytes;

    public PerIpRateLimitFilter(RedisRateLimiter rateLimiter,
                                ObjectMapper objectMapper,
                                AppProperties props,
                                MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.trustedProxy = props.protection().perIpRateLimit().trustedProxy();
        this.deniedCounter = Counter.builder("api.ratelimit.denied").register(meterRegistry);
        try {
            this.rateLimitBodyBytes = objectMapper.writeValueAsBytes(new ErrorResponse(RATE_LIMIT_MESSAGE));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to pre-serialize rate-limit body", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : PublicApiPaths.PREFIXES) {
            if (path.startsWith(prefix)) return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientIp = clientIp(request);
        if (rateLimiter.tryAcquire(clientIp)) {
            chain.doFilter(request, response);
            return;
        }
        deniedCounter.increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(rateLimitBodyBytes.length);
        response.getOutputStream().write(rateLimitBodyBytes);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustedProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                int rawLen = (comma == -1) ? xff.length() : comma;
                // Reject before allocating a substring of an attacker-sized header.
                if (rawLen <= FIRST_HOP_RAW_MAX_LENGTH) {
                    String firstHop = (comma == -1 ? xff : xff.substring(0, comma)).trim();
                    if (isValidIp(firstHop)) {
                        return firstHop;
                    }
                }
                // Misconfigured proxy or spoofed header — fall back to the socket peer
                // rather than letting an attacker control the rate-limit key.
            }
        }
        return request.getRemoteAddr();
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
