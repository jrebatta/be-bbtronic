package com.game.service;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro en memoria de usuarios con conexión WebSocket activa.
 * Actualizado por WebSocketEventListener en cada connect/disconnect.
 */
@Component
public class WebSocketSessionRegistry {

    // sessionCode → conjunto de usernames conectados
    private final ConcurrentHashMap<String, Set<String>> connected = new ConcurrentHashMap<>();

    public void register(String sessionCode, String username) {
        connected.computeIfAbsent(sessionCode, k -> ConcurrentHashMap.newKeySet()).add(username);
    }

    public void unregister(String sessionCode, String username) {
        Set<String> users = connected.get(sessionCode);
        if (users != null) users.remove(username);
    }

    public boolean isConnected(String sessionCode, String username) {
        Set<String> users = connected.get(sessionCode);
        return users != null && users.contains(username);
    }

    public Set<String> getConnected(String sessionCode) {
        Set<String> users = connected.get(sessionCode);
        return users != null ? Collections.unmodifiableSet(users) : Collections.emptySet();
    }
}
