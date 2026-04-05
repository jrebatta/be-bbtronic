package com.game.service;

import com.game.model.GameSession;
import com.game.model.YoNuncaNunca;
import com.game.repository.GameSessionRepository;
import com.game.repository.YoNuncaNuncaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class YoNuncaNuncaService {

    private final GameSessionRepository gameSessionRepository;
    private final YoNuncaNuncaRepository repository;
    private final Map<String, List<YoNuncaNunca>> sessionQuestions = new HashMap<>();
    private final Map<String, YoNuncaNunca> lastQuestion = new HashMap<>();

    public YoNuncaNuncaService(GameSessionRepository gameSessionRepository,
                               YoNuncaNuncaRepository repository) {
        this.gameSessionRepository = gameSessionRepository;
        this.repository = repository;
    }

    @Transactional
    public void start(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<YoNuncaNunca> questions = repository.findAll();
        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay preguntas disponibles para Yo Nunca Nunca.");
        }
        Collections.shuffle(questions);
        sessionQuestions.put(sessionCode, new ArrayList<>(questions));
        session.setCurrentGame("yo-nunca-nunca");
        gameSessionRepository.save(session);
    }

    public YoNuncaNunca getNext(String sessionCode, String tipo) {
        sessionQuestions.putIfAbsent(sessionCode, new ArrayList<>(repository.findAll()));
        List<YoNuncaNunca> questions = sessionQuestions.get(sessionCode);

        YoNuncaNunca next = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay más preguntas disponibles."));
        questions.remove(next);
        lastQuestion.put(sessionCode, next);
        return next;
    }

    public YoNuncaNunca getLastQuestion(String sessionCode) {
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
