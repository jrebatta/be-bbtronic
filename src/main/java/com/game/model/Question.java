package com.game.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(name = "question")
@Getter
@Setter
@NoArgsConstructor
public class Question implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fromUser;
    private String toUser;
    private String question;

    private boolean anonymous; // Indica si la pregunta es anónima

    @ManyToOne
    @JoinColumn(name = "game_session_id")
    @JsonIgnore // Ignorar la serialización de esta referencia
    private GameSession gameSession;

}
