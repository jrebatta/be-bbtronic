package com.game.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class YoNuncaNunca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String texto;

    @Column(nullable = false)
    private String tipo; // "tranquilo" o "picante"
}
