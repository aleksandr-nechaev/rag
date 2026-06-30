package com.nechaev.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One stored turn of a chat session. Persisted as the value type of the {@code sessions}
 * Spring cache (Redis). Kept as a top-level type — separate from the Spring AI
 * {@code Message} hierarchy — so its serialization schema is stable and explicitly
 * registered for the GraalVM native image (see {@code NativeRuntimeHints}).
 */
public record SessionMessage(Role role, String content) {

    public enum Role {
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT
    }
}
