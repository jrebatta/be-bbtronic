package com.game.service;

import com.game.dto.GameStateDTO;
import com.game.dto.SessionSyncDTO;
import com.game.dto.SyncUserDTO;
import com.game.model.*;
import com.game.repository.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final CulturaPendejaRepository culturaPendejaRepository;
    private final YoNuncaNuncaRepository yoNuncaNuncaRepository;
    private final QuienEsMasProbableRepository quienEsMasProbableRepository;
    private final PreguntasIncomodasRepository preguntasIncomodasRepository;
    private final UserService userService;

    private final Map<String, List<YoNuncaNunca>> yoNuncaNuncaQuestions = new HashMap<>();
    private final Map<String, List<CulturaPendeja>> culturaPendejas = new HashMap<>();
    private final Map<String, Queue<PreguntasIncomodas>> preguntasIncomodas = new HashMap<>();
    private final Map<String, List<QuienEsMasProbable>> quienEsMasProbableQuestions = new HashMap<>();
    private final Map<String, List<User>> sessionUsers = new HashMap<>();
    private final Map<String, Map<String, Integer>> sessionVotes = new HashMap<>();
    private final Map<String, Set<String>> sessionUsersVoted = new HashMap<>();
    private final Map<String, List<User>> remainingUsers = new HashMap<>();

    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository,
                              UserRepository userRepository,
                              QuestionRepository questionRepository,
                              JdbcTemplate jdbcTemplate,
                              SimpMessagingTemplate messagingTemplate,
                              CulturaPendejaRepository culturaPendejaRepository,
                              YoNuncaNuncaRepository yoNuncaNuncaRepository,
                              QuienEsMasProbableRepository quienEsMasProbableRepository,
                              PreguntasIncomodasRepository preguntasIncomodasRepository,
                              UserService userService) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.messagingTemplate = messagingTemplate;
        this.culturaPendejaRepository = culturaPendejaRepository;
        this.yoNuncaNuncaRepository = yoNuncaNuncaRepository;
        this.quienEsMasProbableRepository = quienEsMasProbableRepository;
        this.preguntasIncomodasRepository = preguntasIncomodasRepository;
        this.userService = userService;
    }

    public void resetGameData(String sessionCode) {
        String sql = "CALL reset_game_data(?);";
        jdbcTemplate.update(sql, sessionCode);
    }

    @Transactional
    public GameSession createGameSession(User user) {
        if (userRepository.findByUsername(user.getUsername()).isEmpty()) {
            userRepository.saveAndFlush(user);
        }

        GameSession gameSession = new GameSession();
        gameSession.setCreatorName(user.getUsername());
        gameSession.setSessionCode(generateSessionCode());
        gameSession.setCurrentGame(null); // Inicialmente sin juego activo

        GameSession savedSession = gameSessionRepository.saveAndFlush(gameSession);

        user.setGameSession(savedSession);
        userRepository.saveAndFlush(user);

        return savedSession;
    }

    private String generateSessionCode() {
        Random random = new Random();
        int code = 1000 + random.nextInt(9000);
        return String.valueOf(code);
    }

    public void addUserToSession(String sessionCode, User user) {
        userService.joinSession(sessionCode, user.getUsername());
    }

    public List<User> getUsersInSession(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));

        return session.getUsers();
    }

    @PostConstruct
    public void clearUsersOnStartup() {
        // Preservar las preguntas existentes antes de borrar las sesiones
        List<Question> existingQuestions = questionRepository.findAll();

        // Desvincular las preguntas de las sesiones para evitar que se borren en cascada
        for (Question question : existingQuestions) {
            question.setGameSession(null);
        }
        questionRepository.saveAll(existingQuestions);

        // Ahora es seguro borrar usuarios y sesiones
        userRepository.deleteAll();
        gameSessionRepository.deleteAll();

        System.out.println("All users and sessions have been cleared on startup.");
        System.out.println("Preserved " + existingQuestions.size() + " questions from deletion.");
    }

    public GameSession getGameSessionByCode(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }

    /**
     * Método genérico para marcar que el juego ha iniciado
     * NO específico para ningún juego en particular
     */
    public void startGame(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
        session.setGameStarted(true);
        gameSessionRepository.save(session);
    }

    /**
     * Inicia específicamente el juego de "Preguntas Directas" con sistema de rondas
     * SOLO usar para Preguntas Directas
     */
    @Transactional
    public void startPreguntasDirectas(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));

        session.setGameStarted(true);
        session.setCurrentGame("preguntas-directas");

        // Generar un nuevo ID de ronda único
        String newRoundId = UUID.randomUUID().toString();
        session.setCurrentRoundId(newRoundId);
        session.setRoundStatus("WAITING_QUESTIONS");

        // Resetear todos los usuarios a no listos para la nueva ronda
        session.getUsers().forEach(user -> {
            user.setReady(false);
            userRepository.save(user);
        });

        // Limpiar las preguntas mostradas para la nueva ronda
        session.setShownQuestions(new HashSet<>());

        if (!session.getQuestions().isEmpty()) {
            Question firstQuestion = session.getQuestions().getFirst();
            session.getShownQuestions().add(firstQuestion.getId());
            System.out.println("First question registered in shownQuestions: " + firstQuestion.getId());
        }

        gameSessionRepository.save(session);
        System.out.println("Started Preguntas Directas with round ID: " + newRoundId + " - Status: WAITING_QUESTIONS");
    }

    public Question selectAndSetNextQuestion(String sessionCode, String lastToUser) {
        GameSession gameSession = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));

        String currentRoundId = gameSession.getCurrentRoundId();

        // Filtrar solo las preguntas de la ronda actual
        List<Question> questions = gameSession.getQuestions().stream()
                .filter(q -> currentRoundId != null && currentRoundId.equals(q.getRoundId()))
                .toList();

        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No questions in current round");
        }

        List<Question> filteredQuestions = questions.stream()
                .filter(q -> !gameSession.getShownQuestions().contains(q.getId()) && !q.getToUser().equals(lastToUser))
                .collect(Collectors.toList());

        if (filteredQuestions.isEmpty()) {
            filteredQuestions = questions.stream()
                    .filter(q -> !gameSession.getShownQuestions().contains(q.getId()))
                    .toList();
        }

        if (filteredQuestions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("All questions have been shown");
        }

        Random random = new Random();
        Question nextQuestion = filteredQuestions.get(random.nextInt(filteredQuestions.size()));

        Set<Long> uniqueQuestions = new HashSet<>(gameSession.getShownQuestions());
        uniqueQuestions.add(nextQuestion.getId());
        gameSession.setShownQuestions(uniqueQuestions);

        gameSession.setCurrentQuestionIndex(gameSession.getShownQuestions().size() - 1);

        gameSessionRepository.save(gameSession);

        return nextQuestion;
    }

    public List<Question> getQuestionsForSession(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        String currentRoundId = session.getCurrentRoundId();

        return session.getQuestions().stream()
                .filter(q -> currentRoundId != null && currentRoundId.equals(q.getRoundId()))
                .sorted(Comparator.comparingLong(Question::getId))
                .collect(Collectors.toList());
    }

    public Question getCurrentQuestion(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<Question> orderedQuestions = getQuestionsForSession(sessionCode);
        int currentIndex = session.getCurrentQuestionIndex();

        if (currentIndex < 0 || currentIndex >= orderedQuestions.size()) {
            throw new IllegalArgumentException("Index out of range for current question.");
        }

        Question currentQuestion = orderedQuestions.get(currentIndex);

        synchronized (this) {
            if (!session.getShownQuestions().contains(currentQuestion.getId())) {
                session.getShownQuestions().add(currentQuestion.getId());
                gameSessionRepository.save(session);
                System.out.println("Question registered in shownQuestions: " + currentQuestion.getId());
            } else {
                System.out.println("Question already registered: " + currentQuestion.getId());
            }
        }

        return currentQuestion;
    }

    public Map<String, Object> generateQuestionResponse(Question question, int currentQuestionNumber, int totalQuestions, String message) {
        Map<String, Object> response = new HashMap<>();

        if (question.isAnonymous()) {
            question.setFromUser("Anonymous");
        }

        response.put("question", question);
        response.put("questionNumber", currentQuestionNumber + "/" + totalQuestions);
        if (message != null && !message.isEmpty()) {
            response.put("message", message);
        }
        return response;
    }

    public void saveQuestion(String sessionCode, String fromUser, String toUser, String questionText, boolean anonymous) {
        GameSession session = getGameSessionByCode(sessionCode);

        // SOLO validar rondas si el juego actual es "preguntas-directas"
        if ("preguntas-directas".equals(session.getCurrentGame())) {
            // Validar que existe un roundId activo
            if (session.getCurrentRoundId() == null) {
                throw new IllegalStateException("No hay una ronda activa. Inicia el juego primero.");
            }

            // Validar que la ronda está esperando preguntas
            if (!"WAITING_QUESTIONS".equals(session.getRoundStatus())) {
                throw new IllegalStateException("La ronda ya ha iniciado. No se pueden enviar más preguntas.");
            }
        }

        Question question = new Question();
        question.setFromUser(fromUser);
        question.setToUser(toUser);
        question.setQuestion(questionText);
        question.setAnonymous(anonymous);
        question.setGameSession(session);

        // Solo asignar roundId si existe (para preguntas-directas)
        if (session.getCurrentRoundId() != null) {
            question.setRoundId(session.getCurrentRoundId());
            System.out.println("Pregunta guardada para roundId: " + session.getCurrentRoundId());
        } else {
            System.out.println("Pregunta guardada sin roundId (juego diferente a preguntas-directas)");
        }

        questionRepository.save(question);
    }

    public int getCurrentQuestionNumber(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getCurrentQuestionIndex() + 1;
    }

    public int getTotalQuestions(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        String currentRoundId = session.getCurrentRoundId();

        return (int) session.getQuestions().stream()
                .filter(q -> currentRoundId != null && currentRoundId.equals(q.getRoundId()))
                .count();
    }

    /**
     * Inicia una nueva ronda de preguntas directas
     * Genera un nuevo roundId, resetea usuarios ready y limpia preguntas mostradas
     */
    @Transactional
    public Map<String, Object> startNewRound(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        // Generar nuevo roundId
        String newRoundId = UUID.randomUUID().toString();
        session.setCurrentRoundId(newRoundId);
        session.setRoundStatus("WAITING_QUESTIONS");
        session.setCurrentGame("preguntas-directas");

        // Resetear usuarios ready
        session.getUsers().forEach(user -> {
            user.setReady(false);
            userRepository.save(user);
        });

        // Limpiar preguntas mostradas
        session.setShownQuestions(new HashSet<>());
        session.setCurrentQuestionIndex(0);

        gameSessionRepository.save(session);

        System.out.println("Nueva ronda iniciada - roundId: " + newRoundId);

        Map<String, Object> response = new HashMap<>();
        response.put("roundId", newRoundId);
        response.put("status", "WAITING_QUESTIONS");
        response.put("message", "Nueva ronda iniciada. Los usuarios pueden enviar preguntas.");

        return response;
    }

    /**
     * Inicia la fase de juego de la ronda (cuando todos están ready)
     * Cambia el estado de WAITING_QUESTIONS a IN_PROGRESS
     */
    @Transactional
    public void startRoundPlay(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        if (!"WAITING_QUESTIONS".equals(session.getRoundStatus())) {
            throw new IllegalStateException("La ronda ya está en progreso o completada");
        }

        // Verificar que hay preguntas en la ronda actual
        long questionsCount = session.getQuestions().stream()
                .filter(q -> session.getCurrentRoundId() != null &&
                           session.getCurrentRoundId().equals(q.getRoundId()))
                .count();

        if (questionsCount == 0) {
            throw new IllegalStateException("No hay preguntas para jugar en esta ronda");
        }

        session.setRoundStatus("IN_PROGRESS");
        gameSessionRepository.save(session);

        System.out.println("Ronda " + session.getCurrentRoundId() + " - Estado cambiado a IN_PROGRESS");
    }

    /**
     * Obtiene información completa de la ronda actual
     */
    public Map<String, Object> getRoundInfo(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        Map<String, Object> roundInfo = new HashMap<>();
        roundInfo.put("roundId", session.getCurrentRoundId());
        roundInfo.put("status", session.getRoundStatus());
        roundInfo.put("currentGame", session.getCurrentGame());

        // Contar preguntas de la ronda actual
        if (session.getCurrentRoundId() != null) {
            long questionsCount = session.getQuestions().stream()
                    .filter(q -> session.getCurrentRoundId().equals(q.getRoundId()))
                    .count();
            roundInfo.put("totalQuestions", questionsCount);
            roundInfo.put("shownQuestions", session.getShownQuestions().size());
        } else {
            roundInfo.put("totalQuestions", 0);
            roundInfo.put("shownQuestions", 0);
        }

        // Estado de usuarios ready
        List<User> users = session.getUsers();
        long readyCount = users.stream().filter(User::isReady).count();
        roundInfo.put("usersReady", readyCount);
        roundInfo.put("totalUsers", users.size());
        roundInfo.put("allUsersReady", readyCount == users.size() && users.size() > 0);

        return roundInfo;
    }

    /**
     * Resetea el estado ready de todos los usuarios de una sesión
     */
    @Transactional
    public void resetUsersReady(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        session.getUsers().forEach(user -> {
            user.setReady(false);
            userRepository.save(user);
        });

        System.out.println("Usuarios reseteados a no listos para sesión: " + sessionCode);
    }

    @Transactional
    public void startYoNuncaNunca(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<YoNuncaNunca> questions = yoNuncaNuncaRepository.findAll();
        List<User> users = getUsersInSession(sessionCode);

        if (questions.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("Not enough questions or users to start Yo Nunca Nunca.");
        }

        Collections.shuffle(questions);
        Collections.shuffle(users);

        yoNuncaNuncaQuestions.put(sessionCode, new ArrayList<>(questions));
        sessionUsers.put(sessionCode, new ArrayList<>(users));

        // Establecer el juego actual
        session.setCurrentGame("yo-nunca-nunca");
        gameSessionRepository.save(session);
    }

    @Transactional
    public void startQuienEsMasProbable(String sessionCode) {
        try {
            GameSession session = getGameSessionByCode(sessionCode);
            List<QuienEsMasProbable> questions = quienEsMasProbableRepository.findAll();
            List<User> users = getUsersInSession(sessionCode);

            if (questions.isEmpty()) {
                throw new IllegalStateException("No questions available in the database.");
            }
            if (users.isEmpty()) {
                throw new IllegalStateException("No users registered in the session.");
            }

            Collections.shuffle(questions);
            Collections.shuffle(users);

            quienEsMasProbableQuestions.put(sessionCode, new ArrayList<>(questions));
            sessionUsers.put(sessionCode, new ArrayList<>(users));

            // Establecer el juego actual
            session.setCurrentGame("quien-es-mas-probable");
            gameSessionRepository.save(session);
        } catch (Exception e) {
            quienEsMasProbableQuestions.remove(sessionCode);
            sessionUsers.remove(sessionCode);
            throw e;
        }
    }

    public YoNuncaNunca getNextYoNuncaNunca(String sessionCode, String tipo) {
        yoNuncaNuncaQuestions.putIfAbsent(sessionCode, new LinkedList<>(yoNuncaNuncaRepository.findByTipo(tipo)));
        List<YoNuncaNunca> questions = yoNuncaNuncaQuestions.get(sessionCode);

        List<User> users = sessionUsers.get(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No users available in the session.");
        }

        if (questions.isEmpty()) {
            throw new IllegalStateException("No more questions available.");
        }

        return questions.removeFirst();
    }

    @Transactional
    public void startCulturaPendeja(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<CulturaPendeja> questions = culturaPendejaRepository.findAll();
        List<User> users = getUsersInSession(sessionCode);

        if (questions.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("Not enough questions or users to start Cultura Pendeja.");
        }

        Collections.shuffle(questions);
        Collections.shuffle(users);

        culturaPendejas.put(sessionCode, new ArrayList<>(questions));
        sessionUsers.put(sessionCode, new ArrayList<>(users));

        // Establecer el juego actual
        session.setCurrentGame("cultura-pendeja");
        gameSessionRepository.save(session);
    }

    public CulturaPendeja getNextCulturaPendeja(String sessionCode, String tipo) {
        culturaPendejas.putIfAbsent(sessionCode, new LinkedList<>(culturaPendejaRepository.findByTipo(tipo)));
        List<CulturaPendeja> questions = culturaPendejas.get(sessionCode);

        List<User> users = sessionUsers.get(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No users available in the session.");
        }

        if (questions.isEmpty()) {
            throw new IllegalStateException("No more questions available.");
        }

        return questions.removeFirst();
    }

    private User getRandomUser(String sessionCode) {
        List<User> users = sessionUsers.get(sessionCode);
        List<User> remaining = remainingUsers.computeIfAbsent(sessionCode, k -> new ArrayList<>(users));

        if (remaining.isEmpty()) {
            remaining.addAll(users);
        }

        Random random = new Random();
        return remaining.remove(random.nextInt(remaining.size()));
    }

    public String getNextQuienEsMasProbable(String sessionCode, String tipo) {
        clearVotes(sessionCode);

        quienEsMasProbableQuestions.putIfAbsent(sessionCode, new LinkedList<>(quienEsMasProbableRepository.findByTipo(tipo)));
        List<QuienEsMasProbable> questions = quienEsMasProbableQuestions.get(sessionCode);

        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("No more questions available.");
        }

        QuienEsMasProbable question = questions.removeFirst();
        String questionText = question.getTexto();

        List<User> users = getUsersInSession(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No users available in the session.");
        }

        if (questionText.contains("{player}")) {
            User randomUser = getRandomUser(sessionCode);
            questionText = questionText.replace("{player}", randomUser.getUsername());
        }

        return questionText;
    }

    public boolean checkAllUsersVoted(String sessionCode) {
        List<User> users = getUsersInSession(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);

        if (users == null || usersVoted == null) {
            return false;
        }

        for (User user : users) {
            if (!usersVoted.contains(user.getUsername())) {
                return false;
            }
        }
        return true;
    }

    public void registerVote(String sessionCode, String votingUser, String votedUser) {
        sessionVotes.putIfAbsent(sessionCode, new HashMap<>());
        sessionUsersVoted.putIfAbsent(sessionCode, new HashSet<>());

        Map<String, Integer> votes = sessionVotes.get(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);

        if (!usersVoted.contains(votingUser)) {
            votes.put(votedUser, votes.getOrDefault(votedUser, 0) + 1);
            usersVoted.add(votingUser);
        } else {
            throw new IllegalStateException("User has already voted.");
        }

        if (checkAllUsersVoted(sessionCode)) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "voteCompleted", "results", getVoteResults(sessionCode)));
        }
    }

    public String getVoteResults(String sessionCode) {
        Map<String, Integer> votes = sessionVotes.get(sessionCode);

        if (votes == null || votes.isEmpty()) {
            throw new IllegalStateException("No votes registered.");
        }

        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow(() -> new IllegalStateException("No votes available."))
                .getKey();
    }

    public void clearVotes(String sessionCode) {
        sessionVotes.remove(sessionCode);
        sessionUsersVoted.remove(sessionCode);
    }

    @Transactional
    public void startPreguntasIncomodas(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<PreguntasIncomodas> preguntas = preguntasIncomodasRepository.findAll();
        List<User> users = getUsersInSession(sessionCode);

        if (preguntas.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("Not enough questions or users to start Preguntas Incómodas.");
        }

        Collections.shuffle(preguntas);

        preguntasIncomodas.put(sessionCode, new LinkedList<>(preguntas));
        sessionUsers.put(sessionCode, users);

        // Establecer el juego actual
        session.setCurrentGame("preguntas-incomodas");

        // Generar un nuevo ID de ronda único
        String newRoundId = UUID.randomUUID().toString();
        session.setCurrentRoundId(newRoundId);
        session.setRoundStatus("IN_PROGRESS");

        // Resetear todos los usuarios a no listos para la nueva ronda
        session.getUsers().forEach(user -> {
            user.setReady(false);
            userRepository.save(user);
        });

        gameSessionRepository.save(session);
        System.out.println("Started Preguntas Incómodas with round ID: " + newRoundId);

        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"preguntasIncomodasStarted\"}");
    }

    public Map<String, Object> getNextPreguntasIncomodas(String sessionCode, String tipo) {
        Queue<PreguntasIncomodas> questions = preguntasIncomodas.get(sessionCode);
        List<User> users = sessionUsers.get(sessionCode);

        if (questions == null || questions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("No more questions available.");
        }

        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No users in session.");
        }

        PreguntasIncomodas question = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No questions of specified type."));

        questions.remove(question);

        User randomUser = getRandomUser(sessionCode);

        String updatedText = question.getTexto().replace("{player}", randomUser.getUsername());
        question.setTexto(updatedText);

        messagingTemplate.convertAndSend("/topic/" + sessionCode, Map.of("event", "nextQuestion", "question", updatedText, "toUser", randomUser.getUsername()));

        return Map.of(
                "question", updatedText,
                "toUser", randomUser.getUsername()
        );
    }

    /**
     * Obtiene el estado completo de sincronización de una sesión
     * @param sessionCode Código de la sesión
     * @param username Usuario que solicita la sincronización (opcional, usado para hasVoted)
     * @return DTO con toda la información de sincronización
     */
    @Transactional(readOnly = true)
    public SessionSyncDTO getSessionSync(String sessionCode, String username) {
        // Obtener la sesión
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionCode));

        // Convertir usuarios a DTOs
        List<SyncUserDTO> userDTOs = session.getUsers().stream()
                .map(user -> new SyncUserDTO(
                        user.getUsername(),
                        user.isReady(),
                        true, // connected - siempre true por ahora, se puede mejorar con seguimiento de WebSocket
                        user.getSessionToken()
                ))
                .collect(Collectors.toList());

        // Construir GameState si hay un juego activo
        GameStateDTO gameState = null;
        if (session.getCurrentGame() != null && !session.getCurrentGame().isEmpty()) {
            Map<String, Object> currentQuestionData = buildCurrentQuestionData(session, sessionCode, username);

            gameState = new GameStateDTO(
                    session.getRoundStatus() != null ? session.getRoundStatus() : "IN_PROGRESS",
                    session.getCurrentRoundId(),
                    determineGamePhase(session),
                    currentQuestionData
            );
        }

        // Construir la respuesta de sincronización
        return new SessionSyncDTO(
                session.getSessionCode(),
                session.getCreatorName(),
                userDTOs,
                session.getCurrentGame(),
                gameState,
                System.currentTimeMillis()
        );
    }

    /**
     * Construye los datos de la pregunta actual según el tipo de juego
     */
    private Map<String, Object> buildCurrentQuestionData(GameSession session, String sessionCode, String username) {
        if (session.getCurrentGame() == null) {
            return null;
        }

        try {
            switch (session.getCurrentGame()) {
                case "preguntas-directas":
                    return buildPreguntasDirectasData(session);
                case "yo-nunca-nunca":
                    return buildYoNuncaNuncaData(sessionCode);
                case "preguntas-incomodas":
                    return buildPreguntasIncomodasData(sessionCode);
                case "quien-es-mas-probable":
                    return buildQuienEsMasProbableData(sessionCode, username);
                case "cultura-pendeja":
                    return buildCulturaPendejaData(sessionCode);
                default:
                    return null;
            }
        } catch (Exception e) {
            // Si hay error obteniendo datos, retornar null (no romper la sincronización)
            System.err.println("Error building currentQuestionData: " + e.getMessage());
            return null;
        }
    }

    /**
     * Construye datos para Preguntas Directas
     */
    private Map<String, Object> buildPreguntasDirectasData(GameSession session) {
        try {
            // Si no hay preguntas o no estamos en fase de juego, retornar null
            if (!"IN_PROGRESS".equals(session.getRoundStatus()) || session.getShownQuestions().isEmpty()) {
                return null;
            }

            // Obtener la pregunta actual
            List<Question> orderedQuestions = session.getQuestions().stream()
                    .filter(q -> session.getCurrentRoundId() != null &&
                               session.getCurrentRoundId().equals(q.getRoundId()))
                    .sorted(Comparator.comparingLong(Question::getId))
                    .collect(Collectors.toList());

            if (orderedQuestions.isEmpty() || session.getCurrentQuestionIndex() < 0 ||
                session.getCurrentQuestionIndex() >= orderedQuestions.size()) {
                return null;
            }

            Question currentQuestion = orderedQuestions.get(session.getCurrentQuestionIndex());

            Map<String, Object> data = new HashMap<>();
            data.put("question", currentQuestion.getQuestion());
            data.put("fromUser", currentQuestion.isAnonymous() ? "Anonymous" : currentQuestion.getFromUser());
            data.put("toUser", currentQuestion.getToUser());
            data.put("numeroDePregunta", session.getCurrentQuestionIndex() + 1);
            data.put("anonymous", currentQuestion.isAnonymous());

            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Construye datos para Yo Nunca Nunca
     */
    private Map<String, Object> buildYoNuncaNuncaData(String sessionCode) {
        // Para Yo Nunca Nunca, necesitamos acceder a la última pregunta mostrada
        // Como es un juego basado en listas que se van consumiendo, no hay un "índice actual"
        // Retornamos null para que el frontend maneje el estado
        return null;
    }

    /**
     * Construye datos para Preguntas Incómodas
     */
    private Map<String, Object> buildPreguntasIncomodasData(String sessionCode) {
        // Similar a Yo Nunca Nunca, no hay un índice actual persistido
        return null;
    }

    /**
     * Construye datos para Quién Es Más Probable
     */
    private Map<String, Object> buildQuienEsMasProbableData(String sessionCode, String username) {
        // Verificar si hay votación activa
        Map<String, Integer> votes = sessionVotes.get(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);

        if (votes == null || usersVoted == null) {
            return null;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("votingActive", true);
        data.put("hasVoted", username != null && usersVoted.contains(username));

        // Obtener el voto del usuario actual si existe
        String currentUserVote = null;
        if (username != null && usersVoted.contains(username)) {
            // Como no guardamos quien votó a quien individualmente, solo podemos saber si votó
            // Para una implementación completa necesitarías un Map<String, String> de votante -> votado
            currentUserVote = null; // Por ahora null
        }
        data.put("currentUserVote", currentUserVote);

        // No podemos obtener la pregunta actual porque se consume de la lista
        // El frontend debe manejar esto
        return data;
    }

    /**
     * Construye datos para Cultura Pendeja
     */
    private Map<String, Object> buildCulturaPendejaData(String sessionCode) {
        // Similar a los otros juegos basados en listas
        return null;
    }

    /**
     * Determina la fase actual del juego basándose en el estado de la sesión
     */
    private String determineGamePhase(GameSession session) {
        if (session.getRoundStatus() != null) {
            switch (session.getRoundStatus()) {
                case "WAITING_QUESTIONS":
                    return "WAITING_QUESTIONS";
                case "IN_PROGRESS":
                    return "SHOWING_QUESTIONS";
                case "COMPLETED":
                    return "COMPLETED";
                default:
                    return "UNKNOWN";
            }
        }
        return "SHOWING_QUESTIONS"; // Por defecto
    }

    /**
     * Termina el juego actual limpiando el estado del juego
     * @param sessionCode Código de la sesión
     * @return GameSession actualizada
     */
    @Transactional
    public GameSession endCurrentGame(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Sesión no encontrada"));

        // Limpiar el juego actual
        session.setCurrentGame(null);
        session.setCurrentRoundId(null);
        session.setRoundStatus(null);

        // Guardar cambios
        gameSessionRepository.save(session);

        System.out.println("✅ Juego terminado para sesión: " + sessionCode);

        return session;
    }
}


