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

// Per-IP rate-limit filter applied to the expensive paths (RAG entry points).
// Static assets, admin endpoints, and other cheap paths are skipped via shouldNotFilter.
@Component
public class PerIpRateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final Counter deniedCounter;
    // Pre-serialized once: the body is constant, no need to run Jackson on every denial.
    private final byte[] rateLimitBodyBytes;

    public PerIpRateLimitFilter(RedisRateLimiter rateLimiter,
                                ClientIpResolver clientIpResolver,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.deniedCounter = Counter.builder("api.ratelimit.denied").register(meterRegistry);
        try {
            this.rateLimitBodyBytes = objectMapper.writeValueAsBytes(new ErrorResponse(ApiMessages.RATE_LIMIT_MESSAGE));
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
        String clientIp = clientIpResolver.resolve(request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
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
}
