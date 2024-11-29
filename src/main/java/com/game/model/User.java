package com.game.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonBackReference;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor // Genera un constructor sin argumentos
public class User implements Serializable {

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

    // --- Constructor personalizado ---
    public User(String username) {
        this.username = username;
        this.sessionToken = UUID.randomUUID().toString();
        this.ready = false; // Inicialmente, el usuario no está listo
    }
}