package com.nechaev.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Invalidates all resume-derived caches in one place. Called after the resume is
 * re-ingested (see {@code ResumeIngestionService}) so stale answers and chat-history
 * context built from the previous resume are dropped. Fail-open on Redis hiccups is
 * handled by the {@code LoggingCacheErrorHandler} configured in {@code CacheConfig}.
 */
@Service
public class CacheEvictionService {

    @Caching(evict = {
            @CacheEvict(value = "answers", allEntries = true),
            @CacheEvict(value = "sessions", allEntries = true)
    })
    public void evictAll() {
    }
}
