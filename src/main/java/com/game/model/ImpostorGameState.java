package com.game.model;

import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Getter
@Setter
public class ImpostorGameState {

    private String phase = "description"; // description | voting | result | finished

    private int impostorCount;
    private String word;

    private List<String> impostors = new ArrayList<>();
    private List<String> alivePlayers = new ArrayList<>();
    private List<String> eliminatedPlayers = new ArrayList<>();

    // votingUser → votedUser (raw votes, not exposed in sync)
    private Map<String, String> votes = new LinkedHashMap<>();

    private Long votingDeadline;
    private String lastVotingCalledBy;
    private Long lastVotingCalledAt;
    private Long lastVotingEndedAt;

    // per-player cooldown: username → timestamp of last call-vote
    private Map<String, Long> playerLastCallTime = new HashMap<>();

    // palabras ya usadas en esta sesión (persiste entre rondas del mismo juego)
    private Set<String> usedWords = new LinkedHashSet<>();

    // no serializar: referencia al timer de votación activo
    private transient ScheduledFuture<?> votingTimer;
}
