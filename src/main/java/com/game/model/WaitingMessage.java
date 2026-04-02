package com.game.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "waiting_messages")
@Data
@NoArgsConstructor
public class WaitingMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Usa {usuarios} como placeholder para los nombres
    @Column(nullable = false, length = 500)
    private String template;

    public WaitingMessage(String template) {
        this.template = template;
    }
}
