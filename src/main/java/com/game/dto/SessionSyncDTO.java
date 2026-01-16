package com.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSyncDTO {
    private String sessionCode;
    private String creator;
    private List<SyncUserDTO> users;
    private String currentGame;     // null si están en lobby, o nombre del juego actual
    private GameStateDTO gameState; // Estado del juego si hay uno activo
    private long timestamp;         // Marca de tiempo del servidor
}

