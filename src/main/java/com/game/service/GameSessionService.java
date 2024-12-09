package com.game.service;

import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.model.YoNuncaNunca;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
import com.game.repository.YoNuncaNuncaRepository;
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
    private final JdbcTemplate jdbcTemplate = null;
    private final SimpMessagingTemplate messagingTemplate; // Declaración del campo
    private final YoNuncaNuncaRepository yoNuncaNuncaRepository;
    private final Map<String, List<YoNuncaNunca>> sessionQuestions = new HashMap<>();
    private final Map<String, List<User>> sessionUsers = new HashMap<>();



    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository,
                              UserRepository userRepository,
                              QuestionRepository questionRepository,
                              SimpMessagingTemplate messagingTemplate, YoNuncaNuncaRepository yoNuncaNuncaRepository) { // Inyección del template
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.messagingTemplate = messagingTemplate; // Inicialización del template
        this.yoNuncaNuncaRepository = yoNuncaNuncaRepository;
    }

    public void resetGameData(String sessionCode) {
        // Ejecuta el procedimiento almacenado con el código de sesión proporcionado
        String sql = "CALL reset_game_data(?);";
        jdbcTemplate.update(sql, sessionCode);
    }


    @Transactional
    public GameSession createGameSession(User user) {
        // Verificar si el usuario ya existe
        if (userRepository.findByUsername(user.getUsername()).isEmpty()) {
            userRepository.saveAndFlush(user); // Persistir el usuario si no existe
        }

        // Crear la sesión de juego
        GameSession gameSession = new GameSession();
        gameSession.setCreatorName(user.getUsername());
        gameSession.setSessionCode(generateSessionCode());

        // Guardar y forzar la persistencia de la sesión
        GameSession savedSession = gameSessionRepository.saveAndFlush(gameSession);

        // Asociar el usuario a la sesión guardada
        user.setGameSession(savedSession);
        userRepository.saveAndFlush(user); // Guardar el usuario con el session_id

        return savedSession;
    }


    // Generar un código de 4 dígitos
    private String generateSessionCode() {
        Random random = new Random();
        int code = 1000 + random.nextInt(9000); // Genera un número entre 1000 y 9999
        return String.valueOf(code);
    }

    // Agregar un usuario a una sesión existente
    public void addUserToSession(String sessionCode, User user) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        // Verificar si el usuario ya está en la sesión
        if (session.getUsers().stream().anyMatch(u -> u.getUsername().equals(user.getUsername()))) {
            throw new IllegalArgumentException("El usuario ya está en la sesión");
        }

        // Añadir el usuario a la sesión y guardar
        user.setGameSession(session);
        userRepository.save(user);

        session.addUser(user);
        gameSessionRepository.save(session);
    }

    // Obtener la lista de usuarios en una sesión específica
    public List<User> getUsersInSession(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        return session.getUsers(); // Devolver la lista de usuarios en la sesión
    }

    // Método que borra todas las sesiones al iniciar la aplicación
    @PostConstruct
    public void clearUsersOnStartup() {
        // Eliminar todos los usuarios primero
        userRepository.deleteAll();

        // Ahora eliminar todas las sesiones
        gameSessionRepository.deleteAll();

        System.out.println("Todos los usuarios y sesiones han sido borrados al iniciar la aplicación.");
    }


    // Nuevo método para obtener una sesión por su código
    public GameSession getGameSessionByCode(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));
    }

    public void startGame(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));
        session.setGameStarted(true);

        // Registrar la primera pregunta en shownQuestions
        if (!session.getQuestions().isEmpty()) {
            Question firstQuestion = session.getQuestions().getFirst();
            session.getShownQuestions().add(firstQuestion.getId());
            System.out.println("Primera pregunta registrada en shownQuestions: " + firstQuestion.getId());
        }

        gameSessionRepository.save(session); // Guardar los cambios
    }



    public boolean checkAllUsersReady(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));
        return session.getUsers().stream().allMatch(User::isReady);
    }


    // Selecciona la siguiente pregunta aleatoria y sincroniza en la sesión
    public Question selectAndSetNextQuestion(String sessionCode, String lastToUser) {
        GameSession gameSession = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        List<Question> questions = new ArrayList<>(gameSession.getQuestions());
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No hay preguntas en la sesión");
        }

        // Filtrar preguntas no mostradas y que no estén dirigidas al último usuario
        List<Question> filteredQuestions = questions.stream()
                .filter(q -> !gameSession.getShownQuestions().contains(q.getId()) && !q.getToUser().equals(lastToUser))
                .collect(Collectors.toList());

        // Si no hay preguntas disponibles en el filtro inicial, considerar todas las no mostradas
        if (filteredQuestions.isEmpty()) {
            filteredQuestions = questions.stream()
                    .filter(q -> !gameSession.getShownQuestions().contains(q.getId()))
                    .toList();
        }

        // Si todavía no hay preguntas disponibles
        if (filteredQuestions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("Todas las preguntas ya fueron mostradas");
        }

        // Seleccionar una pregunta aleatoria
        Random random = new Random();
        Question nextQuestion = filteredQuestions.get(random.nextInt(filteredQuestions.size()));

        // Validar y eliminar duplicados en shownQuestions
        Set<Long> uniqueQuestions = new HashSet<>(gameSession.getShownQuestions());
        uniqueQuestions.add(nextQuestion.getId());
        gameSession.setShownQuestions(uniqueQuestions);

        // Actualizar el índice de la pregunta actual
        gameSession.setCurrentQuestionIndex(gameSession.getShownQuestions().size() - 1);

        // Persistir cambios en la base de datos
        gameSessionRepository.save(gameSession);

        return nextQuestion;
    }



    public List<Question> getQuestionsForSession(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        // Obtiene las preguntas ordenadas por id (ascendente) para la sesión específica
        return session.getQuestions().stream()
                .sorted(Comparator.comparingLong(Question::getId))
                .collect(Collectors.toList());
    }

    public Question getCurrentQuestion(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<Question> orderedQuestions = getQuestionsForSession(sessionCode);
        int currentIndex = session.getCurrentQuestionIndex();

        if (currentIndex < 0 || currentIndex >= orderedQuestions.size()) {
            throw new IllegalArgumentException("Índice fuera de rango para la pregunta actual.");
        }

        Question currentQuestion = orderedQuestions.get(currentIndex);

        // Verificar y registrar de forma segura que la pregunta no se registre múltiples veces
        synchronized (this) {
            if (!session.getShownQuestions().contains(currentQuestion.getId())) {
                session.getShownQuestions().add(currentQuestion.getId());
                gameSessionRepository.save(session); // Persistir cambios
                System.out.println("Pregunta registrada en shownQuestions: " + currentQuestion.getId());
            } else {
                System.out.println("La pregunta ya estaba registrada: " + currentQuestion.getId());
            }
        }

        return currentQuestion;
    }


    public void saveQuestion(String sessionCode, String fromUser, String toUser, String questionText, boolean anonymous) {
        GameSession session = getGameSessionByCode(sessionCode);

        Question question = new Question();
        question.setFromUser(fromUser);
        question.setToUser(toUser);
        question.setQuestion(questionText);
        question.setAnonymous(anonymous);  // Asigna el valor de anonimato
        question.setGameSession(session);

        questionRepository.save(question);
    }

    public int getCurrentQuestionNumber(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getCurrentQuestionIndex() + 1; // Basado en índice 0
    }

    public int getTotalQuestions(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getQuestions().size();
    }

    public void startYoNuncaNunca(String sessionCode) {
        // Validar la sesión
        GameSession session = getGameSessionByCode(sessionCode);

        // Cargar preguntas y usuarios
        List<YoNuncaNunca> questions = yoNuncaNuncaRepository.findAll(); // Cambia según el filtro necesario
        List<User> users = getUsersInSession(sessionCode);

        if (questions.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("No hay suficientes preguntas o usuarios para iniciar Yo Nunca Nunca.");
        }

        Collections.shuffle(questions);
        Collections.shuffle(users);

        sessionQuestions.put(sessionCode, new ArrayList<>(questions));
        sessionUsers.put(sessionCode, new ArrayList<>(users));
    }



    public Map<String, Object> getNextYoNuncaNunca(String sessionCode) {
        // Obtener o inicializar las preguntas de la sesión
        sessionQuestions.putIfAbsent(sessionCode, yoNuncaNuncaRepository.findAll());
        sessionUsers.putIfAbsent(sessionCode, getUsersInSession(sessionCode));

        List<YoNuncaNunca> questions = sessionQuestions.get(sessionCode);
        List<User> users = sessionUsers.get(sessionCode);

        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        if (users.isEmpty()) {
            // Reiniciar la lista de usuarios si todos ya fueron asignados
            sessionUsers.put(sessionCode, getUsersInSession(sessionCode));
            users = sessionUsers.get(sessionCode);
        }

        // Seleccionar una pregunta y un usuario aleatoriamente
        YoNuncaNunca question = questions.removeFirst(); // Tomar la primera pregunta
        User user = users.removeFirst(); // Tomar el primer usuario

        Map<String, Object> result = new HashMap<>();
        result.put("question", question);
        result.put("user", user);

        return result;
    }

}
