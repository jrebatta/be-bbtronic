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
        userRepository.deleteAll();
        gameSessionRepository.deleteAll();
        System.out.println("All users and sessions have been cleared on startup.");
    }

    public GameSession getGameSessionByCode(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }

    public void startGame(String sessionCode) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
        session.setGameStarted(true);

        if (!session.getQuestions().isEmpty()) {
            Question firstQuestion = session.getQuestions().getFirst();
            session.getShownQuestions().add(firstQuestion.getId());
            System.out.println("First question registered in shownQuestions: " + firstQuestion.getId());
        }

        gameSessionRepository.save(session);
    }

    public Question selectAndSetNextQuestion(String sessionCode, String lastToUser) {
        GameSession gameSession = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));

        List<Question> questions = new ArrayList<>(gameSession.getQuestions());
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No questions in session");
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

        return session.getQuestions().stream()
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

        Question question = new Question();
        question.setFromUser(fromUser);
        question.setToUser(toUser);
        question.setQuestion(questionText);
        question.setAnonymous(anonymous);
        question.setGameSession(session);

        questionRepository.save(question);
    }

    public int getCurrentQuestionNumber(String sessionCode) {
        GameSession session = getGameSessionByCode(sessionCode);
        return session.getCurrentQuestionIndex() + 1;
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
            throw new IllegalStateException("Not enough questions or users to start Yo Nunca Nunca.");
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
                throw new IllegalStateException("No questions available in the database.");
            }
            if (users.isEmpty()) {
                throw new IllegalStateException("No users registered in the session.");
            }

            Collections.shuffle(questions);
            Collections.shuffle(users);

            quienEsMasProbableQuestions.put(sessionCode, new ArrayList<>(questions));
            sessionUsers.put(sessionCode, new ArrayList<>(users));
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
}