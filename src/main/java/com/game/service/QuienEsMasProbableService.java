package com.game.service;

import com.game.model.GameSession;
import com.game.model.QuienEsMasProbable;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuienEsMasProbableRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class QuienEsMasProbableService {

    private final GameSessionRepository gameSessionRepository;
    private final QuienEsMasProbableRepository repository;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, List<QuienEsMasProbable>> sessionQuestions = new HashMap<>();
    private final Map<String, Map<String, Integer>> sessionVotes = new HashMap<>();
    private final Map<String, Set<String>> sessionUsersVoted = new HashMap<>();
    private final Map<String, List<User>> remainingUsers = new HashMap<>();
    private final Map<String, String> lastQuestion = new HashMap<>();

    public QuienEsMasProbableService(GameSessionRepository gameSessionRepository,
                                     QuienEsMasProbableRepository repository,
                                     SimpMessagingTemplate messagingTemplate) {
        this.gameSessionRepository = gameSessionRepository;
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public void start(String sessionCode) {
        GameSession session = getSession(sessionCode);
        List<QuienEsMasProbable> questions = repository.findAll();
        if (questions.isEmpty()) {
            throw new IllegalStateException("No hay preguntas disponibles.");
        }
        Collections.shuffle(questions);
        sessionQuestions.put(sessionCode, new ArrayList<>(questions));
        session.setCurrentGame("quien-es-mas-probable");
        gameSessionRepository.save(session);
    }

    public String getNext(String sessionCode, String tipo) {
        clearVotes(sessionCode);
        sessionQuestions.putIfAbsent(sessionCode, new ArrayList<>(repository.findAll()));
        List<QuienEsMasProbable> questions = sessionQuestions.get(sessionCode);

        QuienEsMasProbable question = questions.stream()
                .filter(q -> q.getTipo().equalsIgnoreCase(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay más preguntas disponibles."));
        questions.remove(question);

        String text = question.getTexto();
        if (text.contains("{player}")) {
            text = text.replace("{player}", getRandomUser(sessionCode).getUsername());
        }
        lastQuestion.put(sessionCode, text);
        return text;
    }

    public String getLastQuestion(String sessionCode) {
        return lastQuestion.get(sessionCode);
    }

    public void registerVote(String sessionCode, String votingUser, String votedUser) {
        sessionVotes.putIfAbsent(sessionCode, new HashMap<>());
        sessionUsersVoted.putIfAbsent(sessionCode, new HashSet<>());

        Map<String, Integer> votes = sessionVotes.get(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);

        if (usersVoted.contains(votingUser)) {
            throw new IllegalStateException("El usuario ya ha votado.");
        }
        votes.put(votedUser, votes.getOrDefault(votedUser, 0) + 1);
        usersVoted.add(votingUser);

        if (checkAllVoted(sessionCode)) {
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "voteCompleted", "results", getVoteResults(sessionCode)));
        }
    }

    public String getVoteResults(String sessionCode) {
        Map<String, Integer> votes = sessionVotes.get(sessionCode);
        if (votes == null || votes.isEmpty()) {
            throw new IllegalStateException("No hay votos registrados.");
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
    }

    public boolean checkAllVoted(String sessionCode) {
        GameSession session = getSession(sessionCode);
        Set<String> usersVoted = sessionUsersVoted.get(sessionCode);
        if (usersVoted == null) return false;
        return session.getUsers().stream().allMatch(u -> usersVoted.contains(u.getUsername()));
    }

    public void clearVotes(String sessionCode) {
        sessionVotes.remove(sessionCode);
        sessionUsersVoted.remove(sessionCode);
    }

    public void cleanup(String sessionCode) {
        sessionQuestions.remove(sessionCode);
        clearVotes(sessionCode);
        remainingUsers.remove(sessionCode);
        lastQuestion.remove(sessionCode);
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
