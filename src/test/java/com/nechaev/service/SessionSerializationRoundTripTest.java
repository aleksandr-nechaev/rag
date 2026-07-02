package com.nechaev.service;

import com.nechaev.config.CacheConfig;
import com.nechaev.model.SessionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips the "sessions" cache value through the exact serializer from CacheConfig.
 * Guards the multi-turn history: it must be stored as a mutable ArrayList, because the
 * immutable list returned by Stream.toList() serializes but cannot be deserialized back —
 * the read would fail-open to empty and silently drop all but the latest turn.
 */
class SessionSerializationRoundTripTest {

    private final GenericJacksonJsonRedisSerializer serializer = CacheConfig.cacheValueSerializer();

    private static List<SessionMessage> twoTurns() {
        return List.of(
                new SessionMessage(SessionMessage.Role.USER, "q1"),
                new SessionMessage(SessionMessage.Role.ASSISTANT, "a1"),
                new SessionMessage(SessionMessage.Role.USER, "q2"),
                new SessionMessage(SessionMessage.Role.ASSISTANT, "a2"));
    }

    @Test
    void arrayListRoundTripsPreservingAllTurns() {
        List<SessionMessage> history = new ArrayList<>(twoTurns());

        byte[] bytes = serializer.serialize(history);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isEqualTo(history);
    }

    @Test
    void immutableListFromStreamToListCannotBeRead() {
        // Documents the trap that broke history: ChatService.saveHistory must NOT use toList().
        byte[] bytes = serializer.serialize(twoTurns());

        assertThatThrownBy(() -> serializer.deserialize(bytes))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void typesOutsideAllowListAreRejectedOnRead() {
        // Guards the polymorphic-typing allow-list in CacheConfig: a @class outside
        // com.nechaev.* / ArrayList must never be instantiated on read, even if the payload
        // was written by us — that is the gadget-chain door for anyone with Redis write access.
        byte[] bytes = serializer.serialize(new java.util.HashMap<String, String>());

        assertThatThrownBy(() -> serializer.deserialize(bytes))
                .isInstanceOf(SerializationException.class);
    }
}
