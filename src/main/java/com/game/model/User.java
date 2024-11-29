package com.game.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonBackReference;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User implements Serializable {

    // --- Campos privados ---

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "session_token", unique = true)
    private String sessionToken;

    @Column(name = "ready", nullable = false)
    private boolean ready = false; // Valor predeterminado: no está listo

    @ManyToOne
    @JoinColumn(name = "game_session_id", nullable = true) // Puede ser nulo inicialmente
    @JsonBackReference
    private GameSession gameSession;

    // --- Constructores ---

    public User() {
    }

    public User(String username) {
        this.username = username;
        this.sessionToken = UUID.randomUUID().toString();
        this.ready = false; // Inicialmente, el usuario no está listo
    }

    public User(String user, boolean b) {
    }

    // --- Getters y Setters ---

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public GameSession getGameSession() {
        return gameSession;
    }

    public void setGameSession(GameSession gameSession) {
        this.gameSession = gameSession;
    }
}
