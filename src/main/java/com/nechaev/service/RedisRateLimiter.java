package com.nechaev.service;

import com.nechaev.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// Fixed-window rate limiter on Redis. Atomic INCR + first-hit EXPIRE via Lua script
// (one round-trip, no race between INCR and EXPIRE). Window key includes the wall-clock
// bucket so each window auto-expires; no manual cleanup. Worst-case burst at the window
// boundary is 2× limitForPeriod within one limitRefreshPeriod (e.g., 120 requests within
// ~1 min when configured for 60/min) — acceptable for anti-abuse purposes.
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    // App-scoped prefix so a shared Redis instance with other services cannot collide
    // on a generic "ratelimit:" key. Keep in sync with other rag:* prefixes if added.
    private static final String KEY_PREFIX = "rag:ratelimit:";

    // KEYS[1] = bucket key, ARGV[1] = TTL seconds. Returns the post-INCR count.
    private static final RedisScript<Long> INCR_AND_EXPIRE = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count",
            Long.class);

    private final StringRedisTemplate redis;
    private final long windowMillis;
    private final int limitForPeriod;
    private final boolean failOpen;
    // Precomputed: TTL is deterministic from windowMillis, no need to recalc per call.
    private final String ttlSecondsArg;

    public RedisRateLimiter(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        AppProperties.PerIpRateLimit cfg = props.protection().perIpRateLimit();
        this.windowMillis = cfg.limitRefreshPeriod().toMillis();
        this.limitForPeriod = cfg.limitForPeriod();
        this.failOpen = cfg.failOpen();
        // TTL = 2 windows so the key outlives the current bucket; cleanup is automatic.
        long ttlSeconds = Math.max(1, Duration.ofMillis(windowMillis * 2).toSeconds());
        this.ttlSecondsArg = String.valueOf(ttlSeconds);
    }

    public boolean tryAcquire(String key) {
        // Programmer-contract violation, distinct from Redis outage: a null/blank key
        // means the caller couldn't identify the client, which would aggregate everyone
        // into one counter. Always fail-closed here — failOpen is reserved for genuine
        // Redis unavailability where service availability outweighs strict accounting.
        if (key == null || key.isBlank()) {
            log.error("RedisRateLimiter received null/blank key — rejecting (caller contract violation)");
            return false;
        }
        long now = System.currentTimeMillis();
        long bucket = now / windowMillis;
        String windowKey = KEY_PREFIX + key + ":" + bucket;
        try {
            Long count = redis.execute(INCR_AND_EXPIRE, List.of(windowKey), ttlSecondsArg);
            return count != null && count <= limitForPeriod;
        } catch (Exception e) {
            log.warn("Redis rate limiter failed (failOpen={}): {}", failOpen, e.getMessage());
            return failOpen;
        }
    }
}
