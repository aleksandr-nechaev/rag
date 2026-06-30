package com.nechaev.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;
    private final HttpSessionHandshakeInterceptor httpSessionHandshakeInterceptor;
    private final StompRateLimitInterceptor stompRateLimitInterceptor;

    public WebSocketConfig(AppProperties appProperties,
                           HttpSessionHandshakeInterceptor httpSessionHandshakeInterceptor,
                           StompRateLimitInterceptor stompRateLimitInterceptor) {
        this.appProperties = appProperties;
        this.httpSessionHandshakeInterceptor = httpSessionHandshakeInterceptor;
        this.stompRateLimitInterceptor = stompRateLimitInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(appProperties.allowedOrigins().toArray(String[]::new))
                .addInterceptors(httpSessionHandshakeInterceptor)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Per-IP rate limit on inbound STOMP SEND frames (handshake is already covered by
        // the HTTP PerIpRateLimitFilter).
        registration.interceptors(stompRateLimitInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
    }
}
