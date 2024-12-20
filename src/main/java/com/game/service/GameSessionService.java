package com.game.service;

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
    private final JdbcTemplate jdbcTemplate = null;
    private final SimpMessagingTemplate messagingTemplate;
    private final CulturaPendejaRepository culturaPendejaRepository;
    private final YoNuncaNuncaRepository yoNuncaNuncaRepository;
    private final QuienEsMasProbableRepository quienEsMasProbableRepository;
    private final PreguntasIncomodasRepository preguntasIncomodasRepository;

    private final Map<String, List<YoNuncaNunca>> yoNuncaNuncaQuestions = new HashMap<>();
    private final Map<String, List<CulturaPendeja>> culturaPendejas = new HashMap<>();
    private final Map<String, Queue<PreguntasIncomodas>> preguntasIncomodas = new HashMap<>();
    private final Map<String, List<QuienEsMasProbable>> quienEsMasProbableQuestions = new HashMap<>();
    private final Map<String, List<User>> sessionUsers = new HashMap<>();
    // Mapa para rastrear votaciones por sesión
    private final Map<String, Map<String, Integer>> sessionVotes = new HashMap<>();
    private final Map<String, Set<String>> sessionUsersVoted = new HashMap<>(); // Registra quién ha votado en cada sesión
    private final Map<String, LinkedList<User>> lastSelectedUsers = new HashMap<>();






    @Autowired
    public GameSessionService(GameSessionRepository gameSessionRepository,
                              UserRepository userRepository,
                              QuestionRepository questionRepository,
                              SimpMessagingTemplate messagingTemplate, CulturaPendejaRepository culturaPendejaRepository,
                              YoNuncaNuncaRepository yoNuncaNuncaRepository,
                              QuienEsMasProbableRepository quienEsMasProbableRepository, PreguntasIncomodasRepository preguntasIncomodasRepository) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.messagingTemplate = messagingTemplate;
        this.culturaPendejaRepository = culturaPendejaRepository;
        this.yoNuncaNuncaRepository = yoNuncaNuncaRepository;
        this.quienEsMasProbableRepository = quienEsMasProbableRepository;
        this.preguntasIncomodasRepository = preguntasIncomodasRepository;
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

    public Map<String, Object> generateQuestionResponse(Question question, int currentQuestionNumber, int totalQuestions, String message) {
        Map<String, Object> response = new HashMap<>();

        // Verificar si la pregunta es anónima
        if (question.isAnonymous()) {
            question.setFromUser("Anónimo");
        }

        response.put("question", question);
        response.put("numeroDePregunta", currentQuestionNumber + "/" + totalQuestions);
        if (message != null && !message.isEmpty()) {
            response.put("message", message);
        }
        return response;
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

    @Transactional
    public void startYoNuncaNunca(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<YoNuncaNunca> questions = yoNuncaNuncaRepository.findAll();
        List<User> users = getUsersInSession(sessionCode);

        if (questions.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("No hay suficientes preguntas o usuarios para iniciar Yo Nunca Nunca.");
        }

        Collections.shuffle(questions);
        Collections.shuffle(users);

        yoNuncaNuncaQuestions.put(sessionCode, new ArrayList<>(questions));
        sessionUsers.put(sessionCode, new ArrayList<>(users));
    }

    @Transactional
    public void startQuienEsMasProbable(String sessionCode) {
        try {
            GameSession session = getGameSessionByCode(sessionCode);
            List<QuienEsMasProbable> questions = quienEsMasProbableRepository.findAll();
            List<User> users = getUsersInSession(sessionCode);

            if (questions.isEmpty()) {
                throw new IllegalStateException("No hay preguntas disponibles en la base de datos.");
            }
            if (users.isEmpty()) {
                throw new IllegalStateException("No hay usuarios registrados en la sesión.");
            }

            Collections.shuffle(questions);
            Collections.shuffle(users);

            quienEsMasProbableQuestions.put(sessionCode, new ArrayList<>(questions));
            sessionUsers.put(sessionCode, new ArrayList<>(users));
        } catch (Exception e) {
            quienEsMasProbableQuestions.remove(sessionCode);
            sessionUsers.remove(sessionCode);
            throw e; // Relanzar la excepción para que se registre el error.
        }
    }

    public YoNuncaNunca getNextYoNuncaNunca(String sessionCode, String tipo) {
        yoNuncaNuncaQuestions.putIfAbsent(sessionCode, new LinkedList<>(yoNuncaNuncaRepository.findByTipo(tipo)));
        List<YoNuncaNunca> questions = yoNuncaNuncaQuestions.get(sessionCode);

        // Verificar si hay usuarios en la sesión
        List<User> users = sessionUsers.get(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No hay usuarios disponibles en la sesión.");
        }

        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        return questions.removeFirst();
    }

    @Transactional
    public void startCulturaPendeja(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        List<CulturaPendeja> questions = culturaPendejaRepository.findAll();
        List<User> users = getUsersInSession(sessionCode);

        if (questions.isEmpty() || users.isEmpty()) {
            throw new IllegalStateException("No hay suficientes preguntas o usuarios para iniciar Cultura Pendeja.");
        }

        Collections.shuffle(questions);
        Collections.shuffle(users);

        culturaPendejas.put(sessionCode, new ArrayList<>(questions));
        sessionUsers.put(sessionCode, new ArrayList<>(users));
    }

    public CulturaPendeja getNextCulturaPendeja(String sessionCode, String tipo) {
        culturaPendejas.putIfAbsent(sessionCode, new LinkedList<>(culturaPendejaRepository.findByTipo(tipo)));
        List<CulturaPendeja> questions = culturaPendejas.get(sessionCode);

        // Verificar si hay usuarios en la sesión
        List<User> users = sessionUsers.get(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No hay usuarios disponibles en la sesión.");
        }

        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        return questions.removeFirst();
    }


    public String getNextQuienEsMasProbable(String sessionCode, String tipo) {
        clearVotes(sessionCode); // Limpia los votos al traer la siguiente pregunta.

        quienEsMasProbableQuestions.putIfAbsent(sessionCode, new LinkedList<>(quienEsMasProbableRepository.findByTipo(tipo)));
        List<QuienEsMasProbable> questions = quienEsMasProbableQuestions.get(sessionCode);

        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        QuienEsMasProbable question = questions.removeFirst();
        String questionText = question.getTexto();

        List<User> users = getUsersInSession(sessionCode);
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No hay usuarios disponibles en la sesión.");
        }

        if (questionText.contains("{player}")) {
            Random random = new Random();
            User randomUser = users.get(random.nextInt(users.size()));
            questionText = questionText.replace("{player}", randomUser.getUsername());
        }

        return questionText;
    }



    public boolean checkAllUsersVoted(String sessionCode) {
        List<User> users = getUsersInSession(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);

        // Validar si hay usuarios y votos registrados
        if (users == null || usersVoted == null) {
            return false;
        }

        // Verificar si todos los usuarios han votado
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

        // Verificar si el usuario ya votó
        if (!usersVoted.contains(votingUser)) {
            votes.put(votedUser, votes.getOrDefault(votedUser, 0) + 1);
            usersVoted.add(votingUser); // Registrar que este usuario ya votó
        } else {
            throw new IllegalStateException("El usuario ya ha votado.");
        }

        // Verificar si todos los usuarios han votado
        if (checkAllUsersVoted(sessionCode)) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "voteCompleted", "results", getVoteResults(sessionCode)));
        }
    }





    public String getVoteResults(String sessionCode) {
        Map<String, Integer> votes = sessionVotes.get(sessionCode);

        if (votes == null || votes.isEmpty()) {
            throw new IllegalStateException("No hay votos registrados.");
        }

        // Encontrar al usuario con más votos
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow(() -> new IllegalStateException("No hay votos disponibles."))
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
            throw new IllegalStateException("No hay suficientes preguntas o usuarios para iniciar Preguntas Incómodas.");
        }

        // Barajamos las preguntas para aleatoriedad
        Collections.shuffle(preguntas);

        // Inicializamos la cola de preguntas para la sesión
        preguntasIncomodas.put(sessionCode, new LinkedList<>(preguntas));

        // Guardamos los usuarios en el mapa correspondiente
        sessionUsers.put(sessionCode, users);

        // Notificamos que el juego comenzó
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"preguntasIncomodasStarted\"}");
    }


    public Map<String, Object> getNextPreguntasIncomodas(String sessionCode, String tipo) {
        Queue<PreguntasIncomodas> questions = preguntasIncomodas.get(sessionCode);
        List<User> users = sessionUsers.get(sessionCode);

        if (questions == null || questions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("No hay usuarios en la sesión.");
        }

        // Filtrar preguntas por tipo
        PreguntasIncomodas question = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay preguntas del tipo especificado."));

        // Removemos la pregunta seleccionada de la cola
        questions.remove(question);

        // Manejamos el historial de usuarios seleccionados
        lastSelectedUsers.putIfAbsent(sessionCode, new LinkedList<>());

        LinkedList<User> recentUsers = lastSelectedUsers.get(sessionCode);
        User randomUser;

        do {
            randomUser = users.get(new Random().nextInt(users.size()));
        } while (recentUsers.contains(randomUser) && users.size() > 2);

        // Actualizamos el historial de usuarios
        recentUsers.addLast(randomUser);
        if (recentUsers.size() > 2) {
            recentUsers.removeFirst();
        }

        // Reemplazamos {player} en el texto de la pregunta
        String updatedText = question.getTexto().replace("{player}", randomUser.getUsername());
        question.setTexto(updatedText);

        // Notificamos la siguiente pregunta
        messagingTemplate.convertAndSend("/topic/" + sessionCode, Map.of("event", "nextQuestion", "question", updatedText, "toUser", randomUser.getUsername()));

        // Devolvemos la pregunta y el usuario en un mapa
        return Map.of(
                "question", updatedText,
                "toUser", randomUser.getUsername()
        );
    }





}
