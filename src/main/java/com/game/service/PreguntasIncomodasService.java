package com.game.service;

import com.game.model.GameSession;
import com.game.model.PreguntasIncomodas;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.PreguntasIncomodasRepository;
import com.game.repository.UserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PreguntasIncomodasService {

    private final GameSessionRepository gameSessionRepository;
    private final UserRepository userRepository;
    private final PreguntasIncomodasRepository repository;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, Queue<PreguntasIncomodas>> sessionQuestions = new HashMap<>();
    private final Map<String, List<User>> remainingUsers = new HashMap<>();

    public PreguntasIncomodasService(GameSessionRepository gameSessionRepository,
                                     UserRepository userRepository,
                                     PreguntasIncomodasRepository repository,
                                     SimpMessagingTemplate messagingTemplate) {
        this.gameSessionRepository = gameSessionRepository;
        this.userRepository = userRepository;
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void start(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<PreguntasIncomodas> preguntas = repository.findAll();
        if (preguntas.isEmpty()) {
            throw new IllegalStateException("No hay preguntas disponibles para Preguntas Incómodas.");
        }
        Collections.shuffle(preguntas);
        sessionQuestions.put(sessionCode, new LinkedList<>(preguntas));

        session.setCurrentGame("preguntas-incomodas");
        session.setCurrentRoundId(UUID.randomUUID().toString());
        session.setRoundStatus("IN_PROGRESS");
        session.getUsers().forEach(u -> {
            u.setReady(false);
            userRepository.save(u);
        });
        gameSessionRepository.save(session);
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"preguntasIncomodasStarted\"}");
    }

    public Map<String, Object> getNext(String sessionCode, String tipo) {
        Queue<PreguntasIncomodas> questions = sessionQuestions.get(sessionCode);
        if (questions == null || questions.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allQuestionsShown\"}");
            throw new IllegalStateException("No hay más preguntas disponibles.");
        }

        PreguntasIncomodas question = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay preguntas del tipo especificado."));
        questions.remove(question);

        User randomUser = getRandomUser(sessionCode);
        String text = question.getTexto().replace("{player}", randomUser.getUsername());
        messagingTemplate.convertAndSend("/topic/" + sessionCode,
                Map.of("event", "nextQuestion", "question", text, "toUser", randomUser.getUsername()));

        return Map.of("question", text, "toUser", randomUser.getUsername());
    }

    public void cleanup(String sessionCode) {
        sessionQuestions.remove(sessionCode);
        remainingUsers.remove(sessionCode);
    }

    private User getRandomUser(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<User> users = session.getUsers();
        List<User> remaining = remainingUsers.computeIfAbsent(sessionCode, k -> new ArrayList<>(users));
        if (remaining.isEmpty()) remaining.addAll(users);
        return remaining.remove(new Random().nextInt(remaining.size()));
    }

    private GameSession getSession(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }
}
