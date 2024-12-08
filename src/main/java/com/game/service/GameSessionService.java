package com.game.service;

import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
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


    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository,
                              UserRepository userRepository,
                              QuestionRepository questionRepository,
                              SimpMessagingTemplate messagingTemplate) { // Inyección del template
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.messagingTemplate = messagingTemplate; // Inicialización del template

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
        gameSession.setShownQuestions(uniqueQuestions); // Asegurar que no haya duplicados

        // Registrar la pregunta como mostrada
        if (!uniqueQuestions.contains(nextQuestion.getId())) {
            uniqueQuestions.add(nextQuestion.getId());
            System.out.println("Pregunta registrada como mostrada: " + nextQuestion.getId());
        } else {
            System.out.println("Pregunta ya estaba registrada: " + nextQuestion.getId());
        }

        gameSessionRepository.save(gameSession); // Persistir cambios en la base de datos

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
    public int getTotalQuestions(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getQuestions().size();
    }

    public int getCurrentQuestionNumber(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getCurrentQuestionIndex() + 1; // Asumiendo que es un índice base 0
    }


}
