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
// Also captures the client IP (resolved the same way as the HTTP per-IP filter) so the
// STOMP message rate limiter can key on it once the socket is open.
@Component
public class HttpSessionHandshakeInterceptor implements HandshakeInterceptor {

    public static final String HTTP_SESSION_ID_ATTR = "HTTP_SESSION_ID";
    public static final String CLIENT_IP_ATTR = "CLIENT_IP";

    private final ClientIpResolver clientIpResolver;

    public HttpSessionHandshakeInterceptor(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var servlet = servletRequest.getServletRequest();
            HttpSession session = servlet.getSession(true);
            attributes.put(HTTP_SESSION_ID_ATTR, session.getId());
            attributes.put(CLIENT_IP_ATTR,
                    clientIpResolver.resolve(servlet.getHeader("X-Forwarded-For"), servlet.getRemoteAddr()));
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
