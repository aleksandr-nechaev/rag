package com.nechaev.service;

import com.nechaev.model.SessionMessage;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Cache facade over the per-session chat history (Redis cache {@code sessions}).
 * The annotated methods live in their own bean so the Spring Cache proxy is actually
 * invoked — calling them from within {@link ChatService} would be a self-invocation and
 * bypass the proxy. Fail-open on Redis hiccups is handled centrally by the
 * {@code LoggingCacheErrorHandler} configured in {@code CacheConfig}.
 */
@Service
public class SessionHistoryStore {

    static final String CACHE = "sessions";

    /** On a cache miss returns an empty list; empty histories are not cached. */
    @Cacheable(value = CACHE, key = "#sessionId", unless = "#result.isEmpty()")
    public List<SessionMessage> load(String sessionId) {
        return List.of();
    }

    @CachePut(value = CACHE, key = "#sessionId")
    public List<SessionMessage> save(String sessionId, List<SessionMessage> history) {
        return history;
    }

    @CacheEvict(value = CACHE, key = "#sessionId")
    public void clear(String sessionId) {
    }

    @CacheEvict(value = CACHE, allEntries = true)
    public void clearAll() {
    }
}
