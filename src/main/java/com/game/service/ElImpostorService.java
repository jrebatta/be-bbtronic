package com.game.service;

import com.game.model.ImpostorGameState;
import com.game.model.ImpostorWord;
import com.game.repository.GameSessionRepository;
import com.game.repository.ImpostorWordRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Service
public class ElImpostorService {

    private static final int VOTING_DURATION_SECONDS = 60;
    private static final int PERSONAL_COOLDOWN_SECONDS = 120;
    private static final int GLOBAL_COOLDOWN_SECONDS = 60;

    private final GameSessionRepository gameSessionRepository;
    private final ImpostorWordRepository wordRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;
    private final WebSocketSessionRegistry sessionRegistry;

    // sessionCode → estado activo del juego
    private final ConcurrentHashMap<String, ImpostorGameState> sessionStates = new ConcurrentHashMap<>();
    // sessionCode → palabras usadas (persiste entre rondas y cuando el juego termina)
    private final ConcurrentHashMap<String, Set<String>> sessionUsedWords = new ConcurrentHashMap<>();

    public ElImpostorService(GameSessionRepository gameSessionRepository,
                              ImpostorWordRepository wordRepository,
                              SimpMessagingTemplate messagingTemplate,
                              TaskScheduler taskScheduler,
                              WebSocketSessionRegistry sessionRegistry) {
        this.gameSessionRepository = gameSessionRepository;
        this.wordRepository = wordRepository;
        this.messagingTemplate = messagingTemplate;
        this.taskScheduler = taskScheduler;
        this.sessionRegistry = sessionRegistry;
    }

    // ─── Inicio ──────────────────────────────────────────────────────────────

    @Transactional
    public void start(String sessionCode, int impostorCount) {
        var session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Sesión no encontrada"));

        List<String> players = session.getUsers().stream()
                .map(u -> u.getUsername())
                .collect(Collectors.toList());

        if (players.isEmpty()) throw new IllegalArgumentException("No hay jugadores en la sesión");
        if (impostorCount < 1) throw new IllegalArgumentException("Debe haber al menos 1 impostor");
        if (impostorCount >= players.size())
            throw new IllegalArgumentException("Debe haber al menos 1 civil");

        // Seleccionar palabra no usada
        sessionUsedWords.putIfAbsent(sessionCode, new LinkedHashSet<>());
        Set<String> usedWords = sessionUsedWords.get(sessionCode);
        List<ImpostorWord> allWords = wordRepository.findAll();
        List<ImpostorWord> available = allWords.stream()
                .filter(w -> !usedWords.contains(w.getWord()))
                .collect(Collectors.toList());
        if (available.isEmpty()) {
            // Todas usadas → resetear historial
            usedWords.clear();
            available = allWords;
        }
        ImpostorWord chosen = available.get(new Random().nextInt(available.size()));
        usedWords.add(chosen.getWord());

        // Asignar roles
        List<String> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        List<String> impostors = new ArrayList<>(shuffled.subList(0, impostorCount));

        // Inicializar estado
        ImpostorGameState state = new ImpostorGameState();
        state.setImpostorCount(impostorCount);
        state.setWord(chosen.getWord());
        state.setImpostors(impostors);
        state.setAlivePlayers(new ArrayList<>(players));
        state.setUsedWords(usedWords);
        state.setPhase("description");
        sessionStates.put(sessionCode, state);

        // Persistir currentGame
        session.setCurrentGame("el-impostor");
        gameSessionRepository.save(session);

        // Broadcast cuenta regresiva async (no bloquea el endpoint)
        for (int i = 3; i >= 1; i--) {
            final int count = i;
            taskScheduler.schedule(
                    () -> messagingTemplate.convertAndSend("/topic/" + sessionCode,
                            Map.of("event", "impostorCountdown", "count", count)),
                    Instant.now().plusSeconds(3 - count));
        }
        taskScheduler.schedule(
                () -> messagingTemplate.convertAndSend("/topic/" + sessionCode,
                        Map.of("event", "impostorGameStarted")),
                Instant.now().plusSeconds(3));
    }

    // ─── Rol privado ─────────────────────────────────────────────────────────

    public Map<String, Object> getMyRole(String sessionCode, String username) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        if (state == null) throw new IllegalArgumentException("No hay juego activo");

        Map<String, Object> result = new HashMap<>();
        if (state.getImpostors().contains(username)) {
            result.put("role", "impostor");
            result.put("word", null);
        } else {
            result.put("role", "civil");
            result.put("word", state.getWord());
        }
        return result;
    }

    // ─── Llamar a votación ────────────────────────────────────────────────────

    public void callVote(String sessionCode, String username) {
        ImpostorGameState state = getActiveState(sessionCode);

        synchronized (state) {
            if (!state.getAlivePlayers().contains(username))
                throw new IllegalArgumentException("El jugador no está entre los vivos");

            if (!"description".equals(state.getPhase()))
                throw new IllegalStateException("No se puede llamar a votación en la fase actual");

            long now = System.currentTimeMillis();

            // Cooldown personal (150 s)
            Long lastCall = state.getPlayerLastCallTime().get(username);
            if (lastCall != null) {
                long elapsed = now - lastCall;
                if (elapsed < PERSONAL_COOLDOWN_SECONDS * 1000L) {
                    long remaining = (PERSONAL_COOLDOWN_SECONDS * 1000L - elapsed) / 1000;
                    throw new IllegalStateException(
                            "Espera " + remaining + " segundos antes de volver a llamar a votación");
                }
            }

            // Cooldown global — activo desde que terminó la última ronda (impostorRoundContinues)
            if (state.getLastVotingEndedAt() != null) {
                long elapsed = now - state.getLastVotingEndedAt();
                if (elapsed < GLOBAL_COOLDOWN_SECONDS * 1000L) {
                    long remaining = (GLOBAL_COOLDOWN_SECONDS * 1000L - elapsed) / 1000;
                    throw new IllegalStateException(
                            "Espera " + remaining + " segundos antes de llamar otra votación");
                }
            }

            // Iniciar votación
            state.setPhase("voting");
            state.setLastVotingCalledBy(username);
            state.setLastVotingCalledAt(now);
            state.getPlayerLastCallTime().put(username, now);
            state.getVotes().clear();

            long deadline = now + VOTING_DURATION_SECONDS * 1000L;
            state.setVotingDeadline(deadline);

            ScheduledFuture<?> timer = taskScheduler.schedule(
                    () -> handleVotingTimerExpired(sessionCode),
                    Instant.now().plusSeconds(VOTING_DURATION_SECONDS));
            state.setVotingTimer(timer);

            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "votingStarted",
                            "calledBy", username,
                            "votingDeadline", deadline,
                            "alivePlayers", new ArrayList<>(state.getAlivePlayers())));
        }
    }

    // ─── Votar ───────────────────────────────────────────────────────────────

    public void vote(String sessionCode, String votingUser, String votedUser) {
        ImpostorGameState state = getActiveState(sessionCode);

        synchronized (state) {
            if (!"voting".equals(state.getPhase()))
                throw new IllegalStateException("No hay votación activa");

            if (!state.getAlivePlayers().contains(votingUser))
                throw new IllegalArgumentException("El jugador votante no está entre los vivos");

            if (!state.getAlivePlayers().contains(votedUser))
                throw new IllegalArgumentException("El jugador votado no está entre los vivos");

            if (state.getVotes().containsKey(votingUser))
                throw new IllegalStateException("El jugador ya ha votado");

            state.getVotes().put(votingUser, votedUser);

            // Calcular quiénes faltan
            List<String> pending = state.getAlivePlayers().stream()
                    .filter(p -> !state.getVotes().containsKey(p))
                    .collect(Collectors.toList());

            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "voteUpdate",
                            "votedCount", state.getVotes().size(),
                            "totalVoters", state.getAlivePlayers().size(),
                            "pendingVoters", pending));

            // Si todos votaron → procesar resultado
            if (pending.isEmpty()) {
                processVotingResult(state, sessionCode);
            }
        }
    }

    // ─── Sincronización ──────────────────────────────────────────────────────

    public Map<String, Object> getSyncState(String sessionCode) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        if (state == null) return null;

        List<String> pendingVoters = "voting".equals(state.getPhase())
                ? state.getAlivePlayers().stream()
                        .filter(p -> !state.getVotes().containsKey(p))
                        .collect(Collectors.toList())
                : Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("phase", state.getPhase());
        result.put("alivePlayers", new ArrayList<>(state.getAlivePlayers()));
        result.put("eliminatedPlayers", new ArrayList<>(state.getEliminatedPlayers()));
        result.put("impostorCount", state.getImpostorCount());
        result.put("votingDeadline", state.getVotingDeadline());
        result.put("pendingVoters", pendingVoters);
        result.put("lastVotingCalledBy", state.getLastVotingCalledBy());
        result.put("lastVotingCalledAt", state.getLastVotingCalledAt());
        result.put("lastVotingEndedAt", state.getLastVotingEndedAt());
        result.put("usedWords", new ArrayList<>(state.getUsedWords()));
        // impostors y word NO se incluyen — solo via /my-role
        return result;
    }

    // ─── Desconexión de jugador ───────────────────────────────────────────────

    public void handlePlayerDisconnect(String sessionCode, String username) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        if (state == null || "finished".equals(state.getPhase())) return;

        synchronized (state) {
            if (!state.getAlivePlayers().contains(username)) return;

            state.getAlivePlayers().remove(username);
            state.getEliminatedPlayers().add(username);

            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "impostorPlayerEliminated",
                            "eliminatedPlayer", username,
                            "reason", "disconnect",
                            "alivePlayers", new ArrayList<>(state.getAlivePlayers())));

            // Si estaba en votación, actualizar pending voters
            if ("voting".equals(state.getPhase())) {
                state.getVotes().remove(username); // invalidar su voto si lo tenía
                List<String> pending = state.getAlivePlayers().stream()
                        .filter(p -> !state.getVotes().containsKey(p))
                        .collect(Collectors.toList());
                if (pending.isEmpty()) {
                    processVotingResult(state, sessionCode);
                    return;
                }
            }

            checkEndCondition(state, sessionCode);
        }
    }

    // ─── Limpieza ─────────────────────────────────────────────────────────────

    public void cleanup(String sessionCode) {
        ImpostorGameState state = sessionStates.remove(sessionCode);
        if (state != null && state.getVotingTimer() != null) {
            state.getVotingTimer().cancel(false);
        }
        // sessionUsedWords se mantiene intencionalmente para no repetir palabras
    }

    public boolean hasActiveGame(String sessionCode) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        return state != null && !"finished".equals(state.getPhase());
    }

    // ─── Lógica interna ──────────────────────────────────────────────────────

    private void handleVotingTimerExpired(String sessionCode) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        if (state == null || !"voting".equals(state.getPhase())) return;

        synchronized (state) {
            if (!"voting".equals(state.getPhase())) return;

            List<String> pending = state.getAlivePlayers().stream()
                    .filter(p -> !state.getVotes().containsKey(p))
                    .collect(Collectors.toList());

            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "votingTimerExpired", "pendingVoters", pending));

            processVotingResult(state, sessionCode);
        }
    }

    /** Debe llamarse con el lock de state ya adquirido. */
    private void processVotingResult(ImpostorGameState state, String sessionCode) {
        // Cancelar timer si aún no expiró
        if (state.getVotingTimer() != null) {
            state.getVotingTimer().cancel(false);
            state.setVotingTimer(null);
        }

        state.setPhase("result");
        state.setVotingDeadline(null);

        // Contar votos recibidos por cada jugador
        Map<String, Long> votesByReceived = state.getVotes().values().stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));

        // Mapa completo con 0 para jugadores sin votos (para broadcast)
        Map<String, Object> voteDisplay = new HashMap<>();
        state.getAlivePlayers().forEach(p -> voteDisplay.put(p, votesByReceived.getOrDefault(p, 0L)));

        // Determinar eliminado
        boolean tie;
        String eliminated;

        if (votesByReceived.isEmpty()) {
            tie = true;
            eliminated = null;
        } else {
            long maxVotes = Collections.max(votesByReceived.values());
            List<String> topVoted = votesByReceived.entrySet().stream()
                    .filter(e -> e.getValue() == maxVotes)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            tie = topVoted.size() > 1;
            eliminated = tie ? null : topVoted.get(0);
        }

        if (eliminated != null) {
            state.getAlivePlayers().remove(eliminated);
            state.getEliminatedPlayers().add(eliminated);
        }

        state.getVotes().clear();

        Map<String, Object> resultMsg = new HashMap<>();
        resultMsg.put("event", "votingResult");
        resultMsg.put("eliminatedPlayer", eliminated);
        resultMsg.put("votes", voteDisplay);
        resultMsg.put("tie", tie);
        resultMsg.put("alivePlayers", new ArrayList<>(state.getAlivePlayers()));
        resultMsg.put("eliminatedPlayers", new ArrayList<>(state.getEliminatedPlayers()));
        messagingTemplate.convertAndSend("/topic/" + sessionCode, resultMsg);

        checkEndCondition(state, sessionCode);
    }

    /** Debe llamarse con el lock de state ya adquirido. */
    private void checkEndCondition(ImpostorGameState state, String sessionCode) {
        List<String> alive = state.getAlivePlayers();
        List<String> impostors = state.getImpostors();

        long impostorsAlive = alive.stream().filter(impostors::contains).count();
        long civiliansAlive = alive.size() - impostorsAlive;

        boolean allImpostorsEliminated = impostorsAlive == 0;
        boolean impostorsMatchOrOutnumberCivilians = impostorsAlive >= civiliansAlive;

        if (alive.isEmpty() || allImpostorsEliminated) {
            endGame(state, sessionCode, "civilians");
        } else if (impostorsMatchOrOutnumberCivilians) {
            endGame(state, sessionCode, "impostors");
        } else {
            // El cooldown global empieza aquí, cuando la ronda continúa
            state.setLastVotingEndedAt(System.currentTimeMillis());
            state.setPhase("description");
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "impostorRoundContinues",
                            "alivePlayers", new ArrayList<>(alive)));
        }
    }

    private void endGame(ImpostorGameState state, String sessionCode, String winner) {
        state.setPhase("finished");

        // Preservar palabras usadas antes de limpiar el estado activo
        sessionUsedWords.put(sessionCode, new HashSet<>(state.getUsedWords()));
        sessionStates.remove(sessionCode);

        // Limpiar currentGame en DB
        gameSessionRepository.findBySessionCode(sessionCode).ifPresent(session -> {
            session.setCurrentGame(null);
            gameSessionRepository.save(session);
        });

        String message = "civilians".equals(winner)
                ? "Todos los impostores fueron eliminados"
                : "Los impostores igualaron en número — ¡ganaron!";

        messagingTemplate.convertAndSend("/topic/" + sessionCode,
                Map.of("event", "impostorGameOver",
                        "winner", winner,
                        "impostors", new ArrayList<>(state.getImpostors()),
                        "message", message));
    }

    private ImpostorGameState getActiveState(String sessionCode) {
        ImpostorGameState state = sessionStates.get(sessionCode);
        if (state == null) throw new IllegalArgumentException("No hay juego activo de El Impostor");
        return state;
    }
}
