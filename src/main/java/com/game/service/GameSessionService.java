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

    public Map<String, String> joinGameSession(String sessionCode, String username) {
        // Validar que la sesión exista
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("El código de sesión es inválido."));

        // Validar que el usuario no exista ya en la sesión
        if (session.getUsers().stream().anyMatch(user -> user.getUsername().equals(username))) {
            throw new IllegalArgumentException("El usuario ya está en la sesión.");
        }

        // Crear un nuevo usuario
        User user = userRepository.findByUsername(username)
                .orElseGet(() -> userRepository.save(new User(username)));

        // Asociar el usuario a la sesión
        user.setGameSession(session);
        userRepository.save(user);

        session.addUser(user);
        gameSessionRepository.save(session);

        // Respuesta de éxito
        Map<String, String> response = new HashMap<>();
        response.put("sessionToken", user.getSessionToken());
        return response;
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
        session.setGameStarted(true); // Asegúrate de tener un campo gameStarted en GameSession
        gameSessionRepository.save(session);
    }


    public boolean checkAllUsersReady(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));
        return session.getUsers().stream().allMatch(User::isReady);
    }


    public void nextQuestion(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        List<Question> orderedQuestions = session.getQuestions().stream()
                .sorted(Comparator.comparingLong(Question::getId))
                .toList();

        int currentIndex = session.getCurrentQuestionIndex();

        // Verifica si hay una siguiente pregunta en la lista ordenada
        if (currentIndex < orderedQuestions.size() - 1) {
            session.setCurrentQuestionIndex(currentIndex + 1);
            gameSessionRepository.save(session);
        }
    }



    // Selecciona la siguiente pregunta aleatoria y sincroniza en la sesión
    public Question selectAndSetNextQuestion(String sessionCode, String lastToUser) {
        GameSession gameSession = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        List<Question> questions = new ArrayList<>(gameSession.getQuestions());
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No hay preguntas en la sesión");
        }

        // Filtra las preguntas para no repetir `toUser` ni preguntas ya mostradas
        List<Question> filteredQuestions = questions.stream()
                .filter(q -> !q.getToUser().equals(lastToUser) && !gameSession.getShownQuestions().contains(q.getId()))
                .collect(Collectors.toList());

        // Si no hay preguntas en filteredQuestions, usamos el conjunto completo excluyendo `lastToUser`
        if (filteredQuestions.isEmpty()) {
            filteredQuestions = questions.stream()
                    .filter(q -> !q.getToUser().equals(lastToUser))
                    .toList();
        }

        // Si todavía no hay preguntas, esto indica que todas las preguntas se han mostrado.
        if (filteredQuestions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("Se mostraron todas las preguntas");
        }

        // Selecciona una pregunta aleatoria de las preguntas filtradas
        Random random = new Random();
        Question nextQuestion = filteredQuestions.get(random.nextInt(filteredQuestions.size()));

        // Actualiza la sesión con la pregunta actual y marca la pregunta como mostrada
        gameSession.setCurrentQuestionIndex(questions.indexOf(nextQuestion));
        gameSession.getShownQuestions().add(nextQuestion.getId());
        gameSessionRepository.save(gameSession);

        return nextQuestion;
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

    public List<Question> getQuestionsForSession(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);

        // Obtiene las preguntas ordenadas por id (ascendente) para la sesión específica
        return session.getQuestions().stream()
                .sorted(Comparator.comparingLong(Question::getId))
                .collect(Collectors.toList());
    }
}
