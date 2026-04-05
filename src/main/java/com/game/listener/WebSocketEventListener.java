package com.game.listener;

import com.game.model.GameSession;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.UserRepository;
import com.game.service.ElImpostorService;
import com.game.service.WebSocketSessionRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Component
public class WebSocketEventListener {

    private static final int GRACE_PERIOD_SECONDS = 20;

    // simpSessionId → username
    private final ConcurrentHashMap<String, String> sessionIdToUsername = new ConcurrentHashMap<>();
    // simpSessionId → sessionCode
    private final ConcurrentHashMap<String, String> sessionIdToSessionCode = new ConcurrentHashMap<>();
    // username → pending disconnect future
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingDisconnects = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final GameSessionRepository gameSessionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;
    private final WebSocketSessionRegistry sessionRegistry;
    private final ElImpostorService elImpostorService;

    public WebSocketEventListener(UserRepository userRepository,
                                   GameSessionRepository gameSessionRepository,
                                   SimpMessagingTemplate messagingTemplate,
                                   TaskScheduler taskScheduler,
                                   WebSocketSessionRegistry sessionRegistry,
                                   ElImpostorService elImpostorService) {
        this.userRepository = userRepository;
        this.gameSessionRepository = gameSessionRepository;
        this.messagingTemplate = messagingTemplate;
        this.taskScheduler = taskScheduler;
        this.sessionRegistry = sessionRegistry;
        this.elImpostorService = elImpostorService;
    }

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String username = sha.getFirstNativeHeader("username");
        String sessionCode = sha.getFirstNativeHeader("sessionCode");
        String simpSessionId = sha.getSessionId();

        if (username == null || sessionCode == null || simpSessionId == null) return;

        sessionIdToUsername.put(simpSessionId, username);
        sessionIdToSessionCode.put(simpSessionId, sessionCode);
        sessionRegistry.register(sessionCode, username);

        // Si había un timer pendiente de desconexión, cancelarlo (Bug 3)
        ScheduledFuture<?> pending = pendingDisconnects.remove(username);
        if (pending != null && pending.cancel(false)) {
            broadcastUserUpdate(sessionCode);
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String simpSessionId = sha.getSessionId();

        String username = sessionIdToUsername.remove(simpSessionId);
        String sessionCode = sessionIdToSessionCode.remove(simpSessionId);

        if (username == null || sessionCode == null) return;

        sessionRegistry.unregister(sessionCode, username);

        messagingTemplate.convertAndSend("/topic/" + sessionCode,
                Map.of("event", "userReconnecting", "username", username));

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> handleUserFinalDisconnect(username, sessionCode),
                Instant.now().plusSeconds(GRACE_PERIOD_SECONDS)
        );
        pendingDisconnects.put(username, future);
    }

    @Transactional
    public void handleUserFinalDisconnect(String username, String sessionCode) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();
        Optional<GameSession> sessionOpt = gameSessionRepository.findBySessionCode(sessionCode);
        if (sessionOpt.isEmpty()) return;

        GameSession session = sessionOpt.get();
        boolean wasCreator = username.equals(session.getCreatorName());

        // Si hay partida de El Impostor activa, notificar antes de eliminar al usuario
        if ("el-impostor".equals(session.getCurrentGame())) {
            elImpostorService.handlePlayerDisconnect(sessionCode, username);
        }

        session.getUsers().removeIf(u -> u.getUsername().equals(username));
        user.setGameSession(null);
        gameSessionRepository.save(session);
        userRepository.delete(user);

        List<Map<String, Object>> updatedUsers = session.getUsers().stream()
                .map(u -> Map.<String, Object>of("username", u.getUsername(), "ready", u.isReady()))
                .collect(Collectors.toList());

        if (wasCreator) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "creatorLeft", "username", username, "users", updatedUsers));
        } else {
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "userLeft", "username", username, "users", updatedUsers));
        }
    }

    private void broadcastUserUpdate(String sessionCode) {
        gameSessionRepository.findBySessionCode(sessionCode).ifPresent(session -> {
            List<Map<String, Object>> users = session.getUsers().stream()
                    .map(u -> Map.<String, Object>of("username", u.getUsername(), "ready", u.isReady()))
                    .collect(Collectors.toList());
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "userUpdate", "users", users));
        });
    }
}
