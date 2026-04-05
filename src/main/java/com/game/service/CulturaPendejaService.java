package com.game.service;

import com.game.model.CulturaPendeja;
import com.game.model.GameSession;
import com.game.repository.CulturaPendejaRepository;
import com.game.repository.GameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CulturaPendejaService {

    private final GameSessionRepository gameSessionRepository;
    private final CulturaPendejaRepository repository;
    private final Map<String, List<CulturaPendeja>> sessionQuestions = new HashMap<>();
    private final Map<String, CulturaPendeja> lastQuestion = new HashMap<>();

    public CulturaPendejaService(GameSessionRepository gameSessionRepository,
                                 CulturaPendejaRepository repository) {
        this.gameSessionRepository = gameSessionRepository;
        this.repository = repository;
    }

    @Transactional
    public void start(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<CulturaPendeja> questions = repository.findAll();
        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay preguntas disponibles para Cultura Pendeja.");
        }
        Collections.shuffle(questions);
        sessionQuestions.put(sessionCode, new ArrayList<>(questions));
        session.setCurrentGame("cultura-pendeja");
        gameSessionRepository.save(session);
    }

    public CulturaPendeja getNext(String sessionCode, String tipo) {
        sessionQuestions.putIfAbsent(sessionCode, new ArrayList<>(repository.findAll()));
        List<CulturaPendeja> questions = sessionQuestions.get(sessionCode);

        CulturaPendeja next = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay más preguntas disponibles."));
        questions.remove(next);
        lastQuestion.put(sessionCode, next);
        return next;
    }

    public CulturaPendeja getLastQuestion(String sessionCode) {
        return lastQuestion.get(sessionCode);
    }

    public void cleanup(String sessionCode) {
        sessionQuestions.remove(sessionCode);
        lastQuestion.remove(sessionCode);
    }

    private GameSession getSession(String sessionCode) {
        return gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
    }
}
