package com.game.service;

import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PreguntasDirectasService {

    private final GameSessionRepository gameSessionRepository;
    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public PreguntasDirectasService(GameSessionRepository gameSessionRepository,
                                    UserRepository userRepository,
                                    QuestionRepository questionRepository,
                                    SimpMessagingTemplate messagingTemplate) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.questionRepository = questionRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void start(String sessionCode) {
        GameSession session = getSession(sessionCode);
        session.setGameStarted(true);
        session.setCurrentGame("preguntas-directas");
        session.setCurrentRoundId(UUID.randomUUID().toString());
        session.setRoundStatus("WAITING_QUESTIONS");
        session.getUsers().forEach(u -> {
            u.setReady(false);
            userRepository.save(u);
        });
        session.setShownQuestions(new HashSet<>());
        gameSessionRepository.save(session);
    }

    @Transactional
    public Map<String, Object> startNewRound(String sessionCode) {
        GameSession session = getSession(sessionCode);
        String newRoundId = UUID.randomUUID().toString();
        session.setCurrentRoundId(newRoundId);
        session.setRoundStatus("WAITING_QUESTIONS");
        session.setCurrentGame("preguntas-directas");
        session.getUsers().forEach(u -> {
            u.setReady(false);
            userRepository.save(u);
        });
        session.setShownQuestions(new HashSet<>());
        session.setCurrentQuestionIndex(0);
        gameSessionRepository.save(session);

        Map<String, Object> response = new HashMap<>();
        response.put("roundId", newRoundId);
        response.put("status", "WAITING_QUESTIONS");
        response.put("message", "Nueva ronda iniciada. Los usuarios pueden enviar preguntas.");
        return response;
    }

    public void saveQuestion(String sessionCode, String fromUser, String toUser, String questionText, boolean anonymous) {
        GameSession session = getSession(sessionCode);
        if (session.getCurrentRoundId() == null) {
            throw new IllegalStateException("No hay una ronda activa. Inicia el juego primero.");
        }
        if (!"WAITING_QUESTIONS".equals(session.getRoundStatus())) {
            throw new IllegalStateException("La ronda ya ha iniciado. No se pueden enviar más preguntas.");
        }
        Question question = new Question();
        question.setFromUser(fromUser);
        question.setToUser(toUser);
        question.setQuestion(questionText);
        question.setAnonymous(anonymous);
        question.setGameSession(session);
        question.setRoundId(session.getCurrentRoundId());
        questionRepository.save(question);
    }

    public Question selectAndSetNextQuestion(String sessionCode, String lastToUser) {
        GameSession session = getSession(sessionCode);
        String roundId = session.getCurrentRoundId();

        List<Question> roundQuestions = session.getQuestions().stream()
                .filter(q -> roundId != null && roundId.equals(q.getRoundId()))
                .toList();

        if (roundQuestions.isEmpty()) {
            throw new IllegalArgumentException("No hay preguntas en la ronda actual.");
        }

        List<Question> candidates = roundQuestions.stream()
                .filter(q -> !session.getShownQuestions().contains(q.getId()) && !q.getToUser().equals(lastToUser))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = roundQuestions.stream()
                    .filter(q -> !session.getShownQuestions().contains(q.getId()))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("All questions have been shown");
        }

        Question next = candidates.get(new Random().nextInt(candidates.size()));
        Set<Long> shown = new HashSet<>(session.getShownQuestions());
        shown.add(next.getId());
        session.setShownQuestions(shown);
        session.setCurrentQuestionIndex(shown.size() - 1);
        gameSessionRepository.save(session);
        return next;
    }

    public Question getCurrentQuestion(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<Question> ordered = getOrderedRoundQuestions(session);
        int idx = session.getCurrentQuestionIndex();
        if (idx < 0 || idx >= ordered.size()) {
            throw new IllegalArgumentException("Index fuera de rango para la pregunta actual.");
        }
        Question q = ordered.get(idx);
        synchronized (this) {
            if (!session.getShownQuestions().contains(q.getId())) {
                session.getShownQuestions().add(q.getId());
                gameSessionRepository.save(session);
            }
        }
        return q;
    }

    public int getCurrentQuestionNumber(String sessionCode) {
        return getSession(sessionCode).getCurrentQuestionIndex() + 1;
    }

    public int getTotalQuestions(String sessionCode) {
        GameSession session = getSession(sessionCode);
        String roundId = session.getCurrentRoundId();
        return (int) session.getQuestions().stream()
                .filter(q -> roundId != null && roundId.equals(q.getRoundId()))
                .count();
    }

    public Map<String, Object> generateQuestionResponse(Question question, int number, int total, String message) {
        Map<String, Object> response = new HashMap<>();
        if (question.isAnonymous()) question.setFromUser("Anónimo");
        response.put("question", question);
        response.put("questionNumber", number + "/" + total);
        if (message != null && !message.isEmpty()) response.put("message", message);
        return response;
    }

    private List<Question> getOrderedRoundQuestions(GameSession session) {
        String roundId = session.getCurrentRoundId();
        return session.getQuestions().stream()
                .filter(q -> roundId != null && roundId.equals(q.getRoundId()))
                .sorted(Comparator.comparingLong(Question::getId))
                .collect(Collectors.toList());
    }

    private GameSession getSession(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }
}
