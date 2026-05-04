package com.nechaev.config;

import com.nechaev.service.ChatService;
import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.stereotype.Component;

// Cleans up the chat history bucket in Redis when the Spring-Session HttpSession
// it belongs to is removed — either by TTL expiry or explicit invalidation.
// Replaces the old onDisconnect cleanup, which incorrectly wiped history on every WS drop.
@Component
public class SessionLifecycleListener {

    private final ChatService chatService;

    public SessionLifecycleListener(ChatService chatService) {
        this.chatService = chatService;
    }

    @EventListener
    public void onExpired(SessionExpiredEvent event) {
        chatService.clearSession(event.getSessionId());
    }

    @EventListener
    public void onDeleted(SessionDeletedEvent event) {
        chatService.clearSession(event.getSessionId());
    }
}
