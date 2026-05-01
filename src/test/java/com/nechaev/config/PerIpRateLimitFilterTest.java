package com.nechaev.config;

import com.nechaev.service.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerIpRateLimitFilterTest {

    RedisRateLimiter rateLimiter;
    ObjectMapper objectMapper;
    SimpleMeterRegistry meterRegistry;
    com.nechaev.config.AppProperties props;
    PerIpRateLimitFilter filter;
    FilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisRateLimiter.class);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        AppProperties.PerIpRateLimit cfg = new AppProperties.PerIpRateLimit(60, Duration.ofMinutes(1), false, true);
        AppProperties.Protection protection = new AppProperties.Protection(null, null, cfg);
        props = new AppProperties(null, protection, null, null, null, null, null);
        filter = new PerIpRateLimitFilter(rateLimiter, objectMapper, props, meterRegistry);
        chain = mock(FilterChain.class);
    }

    @Test
    void protectedPathPermittedPassesThrough() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ask");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(deniedCount()).isEqualTo(0);
    }

    @Test
    void protectedPathDeniedReturns429WithJsonBody() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ask");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getContentType()).startsWith("application/json");
        assertThat(resp.getContentAsString()).contains("Too many requests");
        assertThat(deniedCount()).isEqualTo(1);
    }

    @Test
    void unprotectedPathSkipsLimiter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/metrics");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void wsHandshakePathIsRateLimited() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/info");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void trustedProxyUsesXForwardedForFirstHop() throws Exception {
        AppProperties.PerIpRateLimit cfg = new AppProperties.PerIpRateLimit(60, Duration.ofMinutes(1), true, true);
        AppProperties.Protection protection = new AppProperties.Protection(null, null, cfg);
        AppProperties propsTrusted = new AppProperties(null, protection, null, null, null, null, null);
        PerIpRateLimitFilter filterTrusted = new PerIpRateLimitFilter(rateLimiter, objectMapper, propsTrusted, meterRegistry);
        when(rateLimiter.tryAcquire("9.9.9.9")).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ask");
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("X-Forwarded-For", "9.9.9.9, 10.0.0.1, 10.0.0.2");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filterTrusted.doFilter(req, resp, chain);

        verify(rateLimiter).tryAcquire("9.9.9.9");
    }

    // Misconfigured proxy or spoofed header: an invalid first hop must not be used as
    // the rate-limit key (otherwise an attacker controls the bucket name).
    @Test
    void trustedProxyFallsBackToRemoteAddrOnInvalidXff() throws Exception {
        AppProperties.PerIpRateLimit cfg = new AppProperties.PerIpRateLimit(60, Duration.ofMinutes(1), true, true);
        AppProperties.Protection protection = new AppProperties.Protection(null, null, cfg);
        AppProperties propsTrusted = new AppProperties(null, protection, null, null, null, null, null);
        PerIpRateLimitFilter filterTrusted = new PerIpRateLimitFilter(rateLimiter, objectMapper, propsTrusted, meterRegistry);
        when(rateLimiter.tryAcquire("1.2.3.4")).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ask");
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("X-Forwarded-For", "not-an-ip-just-garbage, 10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filterTrusted.doFilter(req, resp, chain);

        verify(rateLimiter).tryAcquire("1.2.3.4");
    }

    private double deniedCount() {
        return meterRegistry.counter("api.ratelimit.denied").count();
    }
}
