package com.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncUserDTO {
    private String username;
    private boolean ready;
    private boolean connected;
    private String sessionToken;
}
