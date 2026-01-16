package com.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameStateDTO {
    private String status;      // Estado del juego: WAITING, IN_PROGRESS, COMPLETED
    private String roundId;     // ID de ronda actual (para Preguntas Directas)
    private String phase;       // Fase actual del juego: SHOWING_QUESTIONS, WAITING_ANSWERS, etc.
    private Map<String, Object> currentQuestionData; // Datos de la pregunta actual según el juego
}

