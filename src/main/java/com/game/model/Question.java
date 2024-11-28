package com.game.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "question")
public class Question implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fromUser;
    private String toUser;
    private String question;

    @ManyToOne
    @JoinColumn(name = "game_session_id")
    @JsonIgnore  // Ignorar la serialización de esta referencia
    private GameSession gameSession;

    public Question() {}

    public Question(String fromUser, String toUser, String question) {
        this.fromUser = fromUser;
        this.toUser = toUser;
        this.question = question;
    }

    // Getters y Setters
    public Long getId() {
        return id;
    }

    public String getFromUser() {
        return anonymous ? "Anónimo" : fromUser;  // Devuelve "Anónimo" si es true
    }

    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
    }

    public String getToUser() {
        return toUser;
    }

    public void setToUser(String toUser) {
        this.toUser = toUser;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public GameSession getGameSession() {
        return gameSession;
    }

    public void setGameSession(GameSession gameSession) {
        this.gameSession = gameSession;
    }

    // Otros atributos
    private boolean anonymous;

    // Getters y setters
    public boolean isAnonymous() {
        return anonymous;
    }

    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }
}
