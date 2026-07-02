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
// Also enforces the SUBSCRIBE allow-list: the simple broker would otherwise let any client
// subscribe to arbitrary /queue/** or /topic/** destinations, including another session's
// user queue if its id leaked. Kept in the same inbound interceptor to avoid a second
// @Lazy-cycled bean on the client inbound channel.
//
// On denial the offending frame is dropped (not forwarded further); for SEND a friendly
// message is pushed back to the originating session, mirroring the bulkhead/RequestNotPermitted
// path — the connection stays open rather than being torn down.
@Component
public class StompRateLimitInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompRateLimitInterceptor.class);
    // The only destination clients may subscribe to: the user-queue the controller replies on.
    private static final String ALLOWED_SUBSCRIBE_DESTINATION = "/user" + ApiMessages.WS_REPLY_DESTINATION;
    // Cap for echoing an attacker-controlled destination into the log.
    private static final int LOGGED_DESTINATION_MAX_LENGTH = 100;

    private final RedisRateLimiter rateLimiter;
    private final SimpMessagingTemplate messagingTemplate;
    private final Counter deniedCounter;
    private final Counter subscribeDeniedCounter;

    // @Lazy on the messaging template: it is created by the WebSocket broker configuration,
    // which itself depends on this interceptor being registered — lazy resolution breaks the cycle.
    public StompRateLimitInterceptor(RedisRateLimiter rateLimiter,
                                     @Lazy SimpMessagingTemplate messagingTemplate,
                                     MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.messagingTemplate = messagingTemplate;
        this.deniedCounter = Counter.builder("ws.ratelimit.denied").register(meterRegistry);
        this.subscribeDeniedCounter = Counter.builder("ws.subscribe.denied").register(meterRegistry);
    }

    @Override
    public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (StompCommand.SUBSCRIBE.equals(command)) {
            return checkSubscribe(message, accessor);
        }
        // Only rate-limit client SEND frames; CONNECT/DISCONNECT/heartbeats pass through.
        if (!StompCommand.SEND.equals(command)) {
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

    private @Nullable Message<?> checkSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (ALLOWED_SUBSCRIBE_DESTINATION.equals(destination)) {
            return message;
        }
        subscribeDeniedCounter.increment();
        log.warn("Denied SUBSCRIBE to '{}' from session {} — only {} is allowed.",
                abbreviate(destination), accessor.getSessionId(), ALLOWED_SUBSCRIBE_DESTINATION);
        return null; // drop the frame; the client simply gets no subscription
    }

    private static String abbreviate(@Nullable String destination) {
        if (destination == null) return "<none>";
        return destination.length() <= LOGGED_DESTINATION_MAX_LENGTH
                ? destination
                : destination.substring(0, LOGGED_DESTINATION_MAX_LENGTH) + "…";
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
                    sessionId, ApiMessages.WS_REPLY_DESTINATION,
                    new AnswerResponse(ApiMessages.RATE_LIMIT_MESSAGE), headers.getMessageHeaders());
        } catch (RuntimeException e) {
            // Never let a feedback-delivery hiccup propagate back into the inbound channel.
            log.warn("Failed to deliver WS rate-limit notice to session {}: {}", sessionId, e.getMessage());
        }
    }
}
