package com.game.service;

import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import java.util.Random;
import java.util.stream.Collectors;

@Service
public class GameSessionService {

    private final GameSessionRepository gameSessionRepository;
    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;


    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository, UserRepository userRepository, QuestionRepository questionRepository) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
    }



    // Crear una sesión de juego y asignar al usuario creador
    @Transactional
    public GameSession createGameSession(User user) {
        // Verificar si el usuario ya está en la base de datos (opcional)
        if (!userRepository.findByUsername(user.getUsername()).isPresent()) {
            userRepository.save(user);
        }


        // Crear la sesión de juego y asignar el usuario creador
        GameSession gameSession = new GameSession();
        gameSession.setCreatorName(user.getUsername());
        gameSession.setSessionCode(generateSessionCode());
        gameSession.addUser(user); // Agregar al usuario creador a la sesión

        // Guardar la sesión en la base de datos
        return gameSessionRepository.save(gameSession);
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
        gameSessionRepository.deleteAll();
        System.out.println("Todas las sesiones han sido borradas al iniciar la aplicación.");
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
                .collect(Collectors.toList());

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
                    .collect(Collectors.toList());
        }

        // Si todavía no hay preguntas, esto indica que todas las preguntas se han mostrado.
        if (filteredQuestions.isEmpty()) {
            throw new IllegalArgumentException("Todas las preguntas han sido mostradas");
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
