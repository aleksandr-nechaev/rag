package com.nechaev.config;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.service.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompRateLimitInterceptorTest {

    RedisRateLimiter rateLimiter;
    SimpMessagingTemplate messagingTemplate;
    SimpleMeterRegistry meterRegistry;
    StompRateLimitInterceptor interceptor;
    MessageChannel channel;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisRateLimiter.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        interceptor = new StompRateLimitInterceptor(rateLimiter, messagingTemplate, meterRegistry);
        channel = mock(MessageChannel.class);
    }

    private static Message<byte[]> sendFrame(String sessionId, String clientIp) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/v1/ask");
        accessor.setSessionId(sessionId);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HttpSessionHandshakeInterceptor.CLIENT_IP_ATTR, clientIp);
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void sendWithinLimitPassesThrough() {
        when(rateLimiter.tryAcquire("1.2.3.4")).thenReturn(true);
        Message<byte[]> msg = sendFrame("s1", "1.2.3.4");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(), any(Map.class));
        assertThat(deniedCount()).isEqualTo(0);
    }

    @Test
    void sendOverLimitDroppedAndClientNotified() {
        when(rateLimiter.tryAcquire("1.2.3.4")).thenReturn(false);
        Message<byte[]> msg = sendFrame("s1", "1.2.3.4");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull(); // frame dropped, not forwarded to the controller
        verify(messagingTemplate).convertAndSendToUser(
                eq("s1"), eq("/queue/v1/answers"), any(AnswerResponse.class), any(Map.class));
        assertThat(deniedCount()).isEqualTo(1);
    }

    @Test
    void nonSendFramesAreNotRateLimited() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("s1");
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void subscribeToReplyQueuePassesThrough() {
        Message<byte[]> msg = subscribeFrame("/user/queue/v1/answers");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(rateLimiter, never()).tryAcquire(anyString());
        assertThat(subscribeDeniedCount()).isEqualTo(0);
    }

    @Test
    void subscribeToForeignUserQueueIsDropped() {
        // Direct broker destination of another session's user queue — must never be granted.
        Message<byte[]> msg = subscribeFrame("/queue/v1/answers-userVICTIMSESSION");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        assertThat(subscribeDeniedCount()).isEqualTo(1);
    }

    @Test
    void subscribeWithoutDestinationIsDropped() {
        Message<byte[]> msg = subscribeFrame(null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        assertThat(subscribeDeniedCount()).isEqualTo(1);
    }

    private static Message<byte[]> subscribeFrame(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private double deniedCount() {
        return meterRegistry.counter("ws.ratelimit.denied").count();
    }

    private double subscribeDeniedCount() {
        return meterRegistry.counter("ws.subscribe.denied").count();
    }
}
