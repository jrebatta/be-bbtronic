package com.game.service;

import com.game.dto.GameStateDTO;
import com.game.dto.SessionSyncDTO;
import com.game.dto.SyncUserDTO;
import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GameSessionService {

    private final GameSessionRepository gameSessionRepository;
    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;
    private final YoNuncaNuncaService yoNuncaNuncaService;
    private final CulturaPendejaService culturaPendejaService;
    private final QuienEsMasProbableService quienEsMasProbableService;
    private final PreguntasIncomodasService preguntasIncomodasService;
    private final ElImpostorService elImpostorService;

    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository,
                              UserRepository userRepository,
                              QuestionRepository questionRepository,
                              JdbcTemplate jdbcTemplate,
                              UserService userService,
                              YoNuncaNuncaService yoNuncaNuncaService,
                              CulturaPendejaService culturaPendejaService,
                              QuienEsMasProbableService quienEsMasProbableService,
                              PreguntasIncomodasService preguntasIncomodasService,
                              ElImpostorService elImpostorService) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
        this.yoNuncaNuncaService = yoNuncaNuncaService;
        this.culturaPendejaService = culturaPendejaService;
        this.quienEsMasProbableService = quienEsMasProbableService;
        this.preguntasIncomodasService = preguntasIncomodasService;
        this.elImpostorService = elImpostorService;
    }

    @PostConstruct
    public void clearOnStartup() {
        List<Question> questions = questionRepository.findAll();
        questions.forEach(q -> q.setGameSession(null));
        questionRepository.saveAll(questions);
        userRepository.deleteAll();
        gameSessionRepository.deleteAll();
        System.out.println("Sesiones y usuarios limpiados al iniciar. Preguntas preservadas: " + questions.size());
    }

    public void resetGameData(String sessionCode) {
        jdbcTemplate.update("CALL reset_game_data(?);", sessionCode);
    }

    @Transactional
    public GameSession createGameSession(User user) {
        if (userRepository.findByUsername(user.getUsername()).isEmpty()) {
            userRepository.saveAndFlush(user);
        }
        GameSession session = new GameSession();
        session.setCreatorName(user.getUsername());
        session.setSessionCode(generateSessionCode());
        GameSession saved = gameSessionRepository.saveAndFlush(session);
        user.setGameSession(saved);
        userRepository.saveAndFlush(user);
        return saved;
    }

    private String generateSessionCode() {
        return String.valueOf(1000 + new Random().nextInt(9000));
    }

    public void addUserToSession(String sessionCode, User user) {
        userService.joinSession(sessionCode, user.getUsername());
    }

    public List<User> getUsersInSession(String sessionCode) {
        return getGameSessionByCode(sessionCode).getUsers();
    }

    public GameSession getGameSessionByCode(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }

    public void startGame(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        session.setGameStarted(true);
        gameSessionRepository.save(session);
    }

    @Transactional
    public GameSession endCurrentGame(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        session.setCurrentGame(null);
        session.setCurrentRoundId(null);
        session.setRoundStatus(null);
        return gameSessionRepository.save(session);
    }

    public Map<String, Object> getRoundInfo(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        Map<String, Object> info = new HashMap<>();
        info.put("roundId", session.getCurrentRoundId());
        info.put("status", session.getRoundStatus());
        info.put("currentGame", session.getCurrentGame());

        if (session.getCurrentRoundId() != null) {
            long count = session.getQuestions().stream()
                    .filter(q -> session.getCurrentRoundId().equals(q.getRoundId()))
                    .count();
            info.put("totalQuestions", count);
            info.put("shownQuestions", session.getShownQuestions().size());
        } else {
            info.put("totalQuestions", 0);
            info.put("shownQuestions", 0);
        }

        List<User> users = session.getUsers();
        long readyCount = users.stream().filter(User::isReady).count();
        info.put("usersReady", readyCount);
        info.put("totalUsers", users.size());
        info.put("allUsersReady", !users.isEmpty() && readyCount == users.size());
        return info;
    }

    @Transactional
    public void resetUsersReady(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        session.getUsers().forEach(u -> {
            u.setReady(false);
            userRepository.save(u);
        });
    }

    @Transactional
    public void startRoundPlay(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        if (!"WAITING_QUESTIONS".equals(session.getRoundStatus())) {
            throw new IllegalStateException("La ronda ya está en progreso o completada.");
        }
        long count = session.getQuestions().stream()
                .filter(q -> session.getCurrentRoundId() != null && session.getCurrentRoundId().equals(q.getRoundId()))
                .count();
        if (count == 0) {
            throw new IllegalStateException("No hay preguntas para jugar en esta ronda.");
        }
        session.setRoundStatus("IN_PROGRESS");
        gameSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public SessionSyncDTO getSessionSync(String sessionCode, String username) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionCode));

        List<SyncUserDTO> userDTOs = session.getUsers().stream()
                .map(u -> new SyncUserDTO(u.getUsername(), u.isReady(), true, u.getSessionToken()))
                .collect(Collectors.toList());

        GameStateDTO gameState = null;
        if (session.getCurrentGame() != null && !session.getCurrentGame().isEmpty()) {
            Map<String, Object> questionData = buildQuestionData(session);
            gameState = new GameStateDTO(
                    session.getRoundStatus() != null ? session.getRoundStatus() : "IN_PROGRESS",
                    session.getCurrentRoundId(),
                    determineGamePhase(session),
                    questionData
            );
        }

        return new SessionSyncDTO(session.getSessionCode(), session.getCreatorName(),
                userDTOs, session.getCurrentGame(), gameState, System.currentTimeMillis());
    }

    /**
     * Construye los datos de la pregunta actual según el tipo de juego activo.
     * Permite que el frontend restaure el estado al reconectarse.
     */
    private Map<String, Object> buildQuestionData(GameSession session) {
        String game = session.getCurrentGame();
        String sessionCode = session.getSessionCode();
        return switch (game) {
            case "preguntas-directas" -> buildPreguntasDirectasData(session);
            case "yo-nunca-nunca" -> {
                var q = yoNuncaNuncaService.getLastQuestion(sessionCode);
                yield q != null ? Map.of("currentQuestion", Map.of("texto", q.getTexto())) : Map.of("currentQuestion", (Object) null);
            }
            case "cultura-pendeja" -> {
                var q = culturaPendejaService.getLastQuestion(sessionCode);
                yield q != null ? Map.of("currentQuestion", Map.<String, Object>of(
                        "id", q.getId(), "texto", q.getTexto(), "tipo", q.getTipo()
                )) : Map.of("currentQuestion", (Object) null);
            }
            case "quien-es-mas-probable" -> {
                var q = quienEsMasProbableService.getLastQuestion(sessionCode);
                yield Map.of("currentQuestion", q != null ? q : (Object) null);
            }
            case "preguntas-incomodas" -> {
                var q = preguntasIncomodasService.getLastQuestion(sessionCode);
                yield q != null ? Map.of("currentQuestion", Map.<String, Object>of(
                        "question", q.get("question"), "toUser", q.get("toUser")
                )) : Map.of("currentQuestion", (Object) null);
            }
            case "el-impostor" -> elImpostorService.getSyncState(sessionCode);
            default -> null;
        };
    }

    private Map<String, Object> buildPreguntasDirectasData(GameSession session) {
        try {
            if (!"IN_PROGRESS".equals(session.getRoundStatus()) || session.getShownQuestions().isEmpty()) return null;
            List<Question> ordered = session.getQuestions().stream()
                    .filter(q -> session.getCurrentRoundId() != null && session.getCurrentRoundId().equals(q.getRoundId()))
                    .sorted(Comparator.comparingLong(Question::getId))
                    .collect(Collectors.toList());
            int idx = session.getCurrentQuestionIndex();
            if (ordered.isEmpty() || idx < 0 || idx >= ordered.size()) return null;
            Question q = ordered.get(idx);
            Map<String, Object> data = new HashMap<>();
            data.put("currentQuestion", Map.of(
                    "question", q.getQuestion(),
                    "fromUser", q.isAnonymous() ? "Anonymous" : q.getFromUser(),
                    "toUser", q.getToUser(),
                    "numeroDePregunta", idx + 1,
                    "anonymous", q.isAnonymous()
            ));
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private String determineGamePhase(GameSession session) {
        if (session.getRoundStatus() == null) return "SHOWING_QUESTIONS";
        return switch (session.getRoundStatus()) {
            case "WAITING_QUESTIONS" -> "WAITING_QUESTIONS";
            case "IN_PROGRESS" -> "SHOWING_QUESTIONS";
            case "COMPLETED" -> "COMPLETED";
            default -> "UNKNOWN";
        };
    }
}
