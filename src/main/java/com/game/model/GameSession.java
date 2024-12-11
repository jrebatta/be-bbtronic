package com.game.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "game_session")
@Getter
@Setter
@NoArgsConstructor // Genera un constructor sin argumentos
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionCode; // Código de 4 dígitos
    private LocalDateTime createdAt = LocalDateTime.now();

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

    // --- Métodos personalizados ---

    public void addUser(User user) {
        this.users.add(user);
        user.setGameSession(this); // Enlace bidireccional
    }
}
