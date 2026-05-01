package com.nechaev.config;

import java.util.List;

// Single source of truth for "what counts as our public API surface" — used by
// PerIpRateLimitFilter (path prefixes) and SecurityConfig (CSRF-exempt Ant patterns).
// Lives in its own holder class so neither component depends on the other for the
// list, avoiding static-initialization coupling.
public final class PublicApiPaths {

    public static final List<String> PREFIXES = List.of("/api/v1/", "/ws/");

    public static final List<String> ANT_PATTERNS = PREFIXES.stream()
            .map(prefix -> prefix + "**")
            .toList();

    // Spring Security's requestMatchers/ignoringRequestMatchers take String[]; expose a
    // fresh array each call so callers cannot mutate a shared static instance and silently
    // alter security policy elsewhere (a public static final array is final-by-reference,
    // not by content).
    public static String[] antPatternsArray() {
        return ANT_PATTERNS.toArray(String[]::new);
    }

    private PublicApiPaths() {}
}
