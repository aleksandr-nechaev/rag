package com.nechaev.config;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.service.RedisRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

// Per-IP rate limit for STOMP SEND frames over an already-open WebSocket. The HTTP
// PerIpRateLimitFilter only guards the handshake/SockJS transport URLs, not individual
// messages sent over a native WebSocket connection — this closes that gap, keyed on the
// client IP captured at handshake (HttpSessionHandshakeInterceptor.CLIENT_IP_ATTR).
//
// On denial the offending frame is dropped (not forwarded to the controller) and a friendly
// message is pushed back to the originating session, mirroring the bulkhead/RequestNotPermitted
// path — the connection stays open rather than being torn down.
@Component
public class StompRateLimitInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompRateLimitInterceptor.class);
    // Same destination the controller replies on (@SendToUser value); the client subscribes
    // to /user/queue/v1/answers.
    private static final String REPLY_DESTINATION = "/queue/v1/answers";
    private static final String RATE_LIMIT_MESSAGE = "Too many requests, please try again in a few seconds.";

    private final RedisRateLimiter rateLimiter;
    private final SimpMessagingTemplate messagingTemplate;
    private final Counter deniedCounter;

    // @Lazy on the messaging template: it is created by the WebSocket broker configuration,
    // which itself depends on this interceptor being registered — lazy resolution breaks the cycle.
    public StompRateLimitInterceptor(RedisRateLimiter rateLimiter,
                                     @Lazy SimpMessagingTemplate messagingTemplate,
                                     MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.messagingTemplate = messagingTemplate;
        this.deniedCounter = Counter.builder("ws.ratelimit.denied").register(meterRegistry);
    }

    @Override
    public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        // Only rate-limit client SEND frames; CONNECT/SUBSCRIBE/DISCONNECT/heartbeats pass through.
        if (!StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }
        String clientIp = clientIp(accessor);
        if (rateLimiter.tryAcquire(clientIp)) {
            return message;
        }
        deniedCounter.increment();
        sendRateLimitReply(accessor.getSessionId());
        return null; // drop the over-limit frame; keep the connection open
    }

    private static @Nullable String clientIp(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        Object ip = (attrs == null) ? null : attrs.get(HttpSessionHandshakeInterceptor.CLIENT_IP_ATTR);
        return (ip == null) ? null : ip.toString();
    }

    private void sendRateLimitReply(@Nullable String sessionId) {
        if (sessionId == null) {
            return;
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        try {
            messagingTemplate.convertAndSendToUser(
                    sessionId, REPLY_DESTINATION, new AnswerResponse(RATE_LIMIT_MESSAGE), headers.getMessageHeaders());
        } catch (RuntimeException e) {
            // Never let a feedback-delivery hiccup propagate back into the inbound channel.
            log.warn("Failed to deliver WS rate-limit notice to session {}: {}", sessionId, e.getMessage());
        }
    }
}
