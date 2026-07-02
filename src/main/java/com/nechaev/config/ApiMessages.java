package com.nechaev.config;

// Single source of truth for user-facing reply strings (and the STOMP reply destination)
// that are emitted from more than one component. Same pattern as PublicApiPaths: without a
// shared holder the copies drift apart the first time one of them is edited.
public final class ApiMessages {

    // Any "rate limit hit" outcome — per-IP HTTP filter, per-IP STOMP interceptor, or the
    // downstream Resilience4j limiter — must read identically to clients.
    public static final String RATE_LIMIT_MESSAGE = "Too many requests, please try again in a few seconds.";

    // Bulkhead saturation, on both transports.
    public static final String SERVER_BUSY_MESSAGE = "Server is busy, please try again later.";

    // Validation failure — details stay in logs, never echoed back (see ApiExceptionHandler).
    public static final String INVALID_REQUEST_MESSAGE = "Invalid request.";

    // Where WS answers are pushed: @SendToUser in WebSocketChatController and the direct
    // convertAndSendToUser in StompRateLimitInterceptor. Clients subscribe to
    // "/user" + WS_REPLY_DESTINATION.
    public static final String WS_REPLY_DESTINATION = "/queue/v1/answers";

    private ApiMessages() {}
}
