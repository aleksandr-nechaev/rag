package com.nechaev.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// Creates (or reuses) the Spring Session HttpSession on WS handshake and exposes its id
// as a WebSocket session attribute. ChatService keys conversation history by this id, so
// reconnects on any instance behind a load balancer pick up the same dialogue.
@Component
public class HttpSessionHandshakeInterceptor implements HandshakeInterceptor {

    public static final String HTTP_SESSION_ID_ATTR = "HTTP_SESSION_ID";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession(true);
            attributes.put(HTTP_SESSION_ID_ATTR, session.getId());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
