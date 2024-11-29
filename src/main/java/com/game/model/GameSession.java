package com.game.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "game_session")
public class GameSession {

    // --- Campos privados ---

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionCode; // Código de 4 dígitos
    private LocalDateTime createdAt;

    private String creatorName; // Nombre del creador de la sesión
    private boolean gameStarted = false;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int currentQuestionIndex = 0;

    @OneToMany(mappedBy = "gameSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Question> questions = new ArrayList<>();

    @OneToMany(mappedBy = "gameSession", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<User> users = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shown_questions", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "question_id")
    private Set<Long> shownQuestions = new HashSet<>();

    // --- Constructor ---

    public GameSession() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters y Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public void setSessionCode(String sessionCode) {
        this.sessionCode = sessionCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public void setGameStarted(boolean gameStarted) {
        this.gameStarted = gameStarted;
    }

    public int getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }

    public void setCurrentQuestionIndex(int currentQuestionIndex) {
        this.currentQuestionIndex = currentQuestionIndex;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    public Set<Long> getShownQuestions() {
        return shownQuestions;
    }

    public void setShownQuestions(Set<Long> shownQuestions) {
        this.shownQuestions = shownQuestions;
    }

    // --- Métodos personalizados ---

    public void addUser(User user) {
        this.users.add(user);
        user.setGameSession(this); // Enlace bidireccional
    }

    public void addQuestion(Question question) {
        this.questions.add(question);
        question.setGameSession(this); // Enlace bidireccional
    }
}
