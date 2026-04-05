package com.game.controller;

import com.game.dto.SessionSyncDTO;
import com.game.model.GameSession;
import com.game.model.User;
import com.game.model.WaitingMessage;
import com.game.repository.WaitingMessageRepository;
import com.game.service.*;
import com.game.service.ElImpostorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = {"https://fe-bbtronic-vue.vercel.app", "http://localhost:5173"})
@RequestMapping("/api/game-sessions")
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final PreguntasDirectasService preguntasDirectasService;
    private final YoNuncaNuncaService yoNuncaNuncaService;
    private final CulturaPendejaService culturaPendejaService;
    private final QuienEsMasProbableService quienEsMasProbableService;
    private final PreguntasIncomodasService preguntasIncomodasService;
    private final WaitingMessageRepository waitingMessageRepository;
    private final ElImpostorService elImpostorService;

    @Autowired
    public GameSessionController(GameSessionService gameSessionService,
                                 UserService userService,
                                 SimpMessagingTemplate messagingTemplate,
                                 PreguntasDirectasService preguntasDirectasService,
                                 YoNuncaNuncaService yoNuncaNuncaService,
                                 CulturaPendejaService culturaPendejaService,
                                 QuienEsMasProbableService quienEsMasProbableService,
                                 PreguntasIncomodasService preguntasIncomodasService,
                                 WaitingMessageRepository waitingMessageRepository,
                                 ElImpostorService elImpostorService) {
        this.gameSessionService = gameSessionService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.preguntasDirectasService = preguntasDirectasService;
        this.yoNuncaNuncaService = yoNuncaNuncaService;
        this.culturaPendejaService = culturaPendejaService;
        this.quienEsMasProbableService = quienEsMasProbableService;
        this.preguntasIncomodasService = preguntasIncomodasService;
        this.waitingMessageRepository = waitingMessageRepository;
        this.elImpostorService = elImpostorService;
    }

    // ─── Gestión de sesión ────────────────────────────────────────────────────

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> createGameSession(@RequestParam("username") String username) {
        try {
            User user = userService.registerUser(username);
            GameSession session = gameSessionService.createGameSession(user);
            return ResponseEntity.ok(Map.of(
                    "username", user.getUsername(),
                    "sessionToken", user.getSessionToken(),
                    "sessionCode", session.getSessionCode()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}")
    public ResponseEntity<?> getGameSession(@PathVariable String sessionCode) {
        try {
            GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
            List<User> users = gameSessionService.getUsersInSession(sessionCode);
            return ResponseEntity.ok(Map.of(
                    "creator", session.getCreatorName(),
                    "gameStarted", session.isGameStarted(),
                    "users", users,
                    "currentGame", session.getCurrentGame() != null ? session.getCurrentGame() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Código de sesión inválido."));
        }
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinGameSession(@RequestBody Map<String, String> requestData) {
        String sessionCode = requestData.get("sessionCode");
        String username = requestData.get("username");

        if (sessionCode == null || sessionCode.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El código de sesión es obligatorio."));
        }
        if (username == null || username.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre de usuario es obligatorio."));
        }

        try {
            gameSessionService.getGameSessionByCode(sessionCode);
            User user = userService.registerUser(username);
            gameSessionService.addUserToSession(sessionCode, user);

            List<User> updatedUsers = gameSessionService.getUsersInSession(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "userUpdate", "users", updatedUsers));

            return ResponseEntity.ok(Map.of("sessionToken", user.getSessionToken()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/kick")
    public ResponseEntity<?> kickUser(@PathVariable String sessionCode,
                                      @RequestParam String username) {
        try {
            userService.kickUser(sessionCode, username);

            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "kicked", "username", username));

            List<User> updatedUsers = gameSessionService.getUsersInSession(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    Map.of("event", "userLeft", "username", username, "users", updatedUsers));

            return ResponseEntity.ok(Map.of("message", "Usuario expulsado"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetGameData(@RequestBody Map<String, String> body) {
        String sessionCode = body.get("sessionCode");
        if (sessionCode == null || sessionCode.isEmpty()) {
            return ResponseEntity.badRequest().body("El código de sesión es obligatorio.");
        }
        try {
            gameSessionService.resetGameData(sessionCode);
            return ResponseEntity.ok("Datos de la sesión " + sessionCode + " reiniciados correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("No se pudieron reiniciar los datos: " + e.getMessage());
        }
    }

    @GetMapping("/{sessionCode}/sync")
    public ResponseEntity<?> getSessionSync(@PathVariable String sessionCode,
                                            @RequestParam(required = false) String username) {
        try {
            SessionSyncDTO syncData = gameSessionService.getSessionSync(sessionCode, username);
            return ResponseEntity.ok(syncData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Control de juego y rounds ────────────────────────────────────────────

    @PostMapping("/{sessionCode}/start-game")
    public ResponseEntity<Map<String, String>> startGame(@PathVariable String sessionCode) {
        gameSessionService.startGame(sessionCode);
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"gameStarted\"}");
        return ResponseEntity.ok(Map.of("mensaje", "Juego iniciado"));
    }

    @PostMapping("/{sessionCode}/end-game")
    public ResponseEntity<?> endCurrentGame(@PathVariable String sessionCode) {
        try {
            gameSessionService.endCurrentGame(sessionCode);
            cleanupAllGameServices(sessionCode);
            return ResponseEntity.ok(Map.of("message", "Juego terminado exitosamente", "sessionCode", sessionCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/round-info")
    public ResponseEntity<Map<String, Object>> getRoundInfo(@PathVariable String sessionCode) {
        try {
            return ResponseEntity.ok(gameSessionService.getRoundInfo(sessionCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // Separado en POST porque tiene side effect (cambia estado de la ronda)
    @PostMapping("/{sessionCode}/check-all-ready")
    public ResponseEntity<Map<String, Object>> checkAllReady(@PathVariable String sessionCode) {
        GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
        boolean allReady = !session.getUsers().isEmpty() && session.getUsers().stream().allMatch(User::isReady);

        Map<String, Object> response = new HashMap<>();
        response.put("allReady", allReady);
        response.put("roundId", session.getCurrentRoundId());
        response.put("roundStatus", session.getRoundStatus());

        if (allReady) {
            if ("WAITING_QUESTIONS".equals(session.getRoundStatus())) {
                try {
                    gameSessionService.startRoundPlay(sessionCode);
                    response.put("roundStatus", "IN_PROGRESS");
                    response.put("message", "Todos listos. ¡La ronda ha comenzado!");
                } catch (IllegalStateException e) {
                    response.put("message", e.getMessage());
                    return ResponseEntity.ok(response);
                }
            }
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allReady\"}");
            response.put("message", "Todos los usuarios están listos");
        } else {
            List<String> notReady = session.getUsers().stream()
                    .filter(u -> !u.isReady())
                    .map(User::getUsername)
                    .collect(Collectors.toList());
            String usuarios = String.join(", ", notReady);

            List<WaitingMessage> templates = waitingMessageRepository.findAll();
            String waitingMsg;
            if (templates.isEmpty()) {
                waitingMsg = "Aún faltan usuarios por estar listos: " + usuarios;
            } else {
                WaitingMessage picked = templates.get(new Random().nextInt(templates.size()));
                waitingMsg = picked.getTemplate().replace("{usuarios}", usuarios);
            }
            response.put("message", waitingMsg);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionCode}/reset-users-ready")
    public ResponseEntity<Map<String, String>> resetUsersReady(@PathVariable String sessionCode) {
        try {
            gameSessionService.resetUsersReady(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"usersReadyReset\"}");
            return ResponseEntity.ok(Map.of("message", "Usuarios reseteados exitosamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Preguntas Directas ───────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/start-preguntas-directas")
    public ResponseEntity<Map<String, Object>> startPreguntasDirectas(@PathVariable String sessionCode) {
        preguntasDirectasService.start(sessionCode);
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"preguntasDirectasStarted\"}");
        Map<String, Object> roundInfo = gameSessionService.getRoundInfo(sessionCode);
        return ResponseEntity.ok(Map.of(
                "mensaje", "Preguntas Directas iniciado",
                "roundId", roundInfo.get("roundId"),
                "roundStatus", roundInfo.get("status")
        ));
    }

    @PostMapping("/{sessionCode}/start-new-round")
    public ResponseEntity<Map<String, Object>> startNewRound(@PathVariable String sessionCode) {
        try {
            Map<String, Object> response = preguntasDirectasService.startNewRound(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode,
                    "{\"event\":\"newRoundStarted\",\"roundId\":\"" + response.get("roundId") + "\"}");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/send-question")
    public ResponseEntity<?> sendQuestion(@PathVariable String sessionCode,
                                          @RequestBody Map<String, Object> body) {
        try {
            preguntasDirectasService.saveQuestion(
                    sessionCode,
                    (String) body.get("fromUser"),
                    (String) body.get("toUser"),
                    (String) body.get("question"),
                    (Boolean) body.get("anonymous")
            );
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/current-question")
    public ResponseEntity<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionCode) {
        try {
            var question = preguntasDirectasService.getCurrentQuestion(sessionCode);
            int total = preguntasDirectasService.getTotalQuestions(sessionCode);
            int number = preguntasDirectasService.getCurrentQuestionNumber(sessionCode);
            return ResponseEntity.ok(preguntasDirectasService.generateQuestionResponse(question, number, total, null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/{sessionCode}/next-random-question")
    public ResponseEntity<Map<String, Object>> nextRandomQuestion(@PathVariable String sessionCode,
                                                                  @RequestBody Map<String, String> body) {
        if (body == null || !body.containsKey("lastToUser") || body.get("lastToUser").isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var next = preguntasDirectasService.selectAndSetNextQuestion(sessionCode, body.get("lastToUser"));
            int total = preguntasDirectasService.getTotalQuestions(sessionCode);
            int number = preguntasDirectasService.getCurrentQuestionNumber(sessionCode);
            return ResponseEntity.ok(preguntasDirectasService.generateQuestionResponse(next, number, total, "Pregunta siguiente seleccionada"));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(Map.of("message", "Se mostraron todas las preguntas", "allQuestionsShown", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).build();
        }
    }

    // ─── Yo Nunca Nunca ───────────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/yo-nunca-nunca/start")
    public ResponseEntity<?> startYoNuncaNunca(@PathVariable String sessionCode) {
        try {
            yoNuncaNuncaService.start(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"yoNuncaNuncaStarted\"}");
            return ResponseEntity.ok(Map.of("message", "Yo Nunca Nunca iniciado correctamente."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/next-yo-nunca-nunca")
    public ResponseEntity<?> getNextYoNuncaNunca(@PathVariable String sessionCode, @RequestParam String tipo) {
        try {
            return ResponseEntity.ok(yoNuncaNuncaService.getNext(sessionCode, tipo));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body("No hay más preguntas disponibles.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Cultura Pendeja ──────────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/cultura-pendeja/start")
    public ResponseEntity<?> startCulturaPendeja(@PathVariable String sessionCode) {
        try {
            culturaPendejaService.start(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"culturaPendejaStarted\"}");
            return ResponseEntity.ok(Map.of("message", "Cultura Pendeja iniciado correctamente."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/next-cultura-pendeja")
    public ResponseEntity<?> getNextCulturaPendeja(@PathVariable String sessionCode, @RequestParam String tipo) {
        try {
            return ResponseEntity.ok(culturaPendejaService.getNext(sessionCode, tipo));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body("No hay más preguntas disponibles.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Quien Es Más Probable ────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/quien-es-mas-probable/start")
    public ResponseEntity<?> startQuienEsMasProbable(@PathVariable String sessionCode) {
        try {
            quienEsMasProbableService.start(sessionCode);
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"quienEsMasProbableStarted\"}");
            return ResponseEntity.ok(Map.of("message", "Quien Es Más Probable iniciado correctamente."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/next-quien-es-mas-probable")
    public ResponseEntity<?> getNextQuienEsMasProbable(@PathVariable String sessionCode, @RequestParam String tipo) {
        try {
            return ResponseEntity.ok(quienEsMasProbableService.getNext(sessionCode, tipo));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/vote")
    public ResponseEntity<?> registerVote(@PathVariable String sessionCode,
                                          @RequestBody Map<String, String> voteData) {
        String votingUser = voteData.get("votingUser");
        String votedUser = voteData.get("votedUser");
        if (votingUser == null || votingUser.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El usuario votante no puede estar vacío."));
        }
        if (votedUser == null || votedUser.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El usuario votado no puede estar vacío."));
        }
        try {
            quienEsMasProbableService.registerVote(sessionCode, votingUser, votedUser);
            return ResponseEntity.ok(Map.of("message", "Voto registrado."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/vote-results")
    public ResponseEntity<?> getVoteResults(@PathVariable String sessionCode) {
        try {
            return ResponseEntity.ok(Map.of("winner", quienEsMasProbableService.getVoteResults(sessionCode)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/check-all-voted")
    public ResponseEntity<?> checkAllVoted(@PathVariable String sessionCode) {
        try {
            return ResponseEntity.ok(Map.of("allVoted", quienEsMasProbableService.checkAllVoted(sessionCode)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/clear-votes")
    public ResponseEntity<?> clearVotes(@PathVariable String sessionCode) {
        try {
            quienEsMasProbableService.clearVotes(sessionCode);
            return ResponseEntity.ok(Map.of("message", "Votos limpiados."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Preguntas Incómodas ──────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/start-preguntas-incomodas")
    public ResponseEntity<?> startPreguntasIncomodas(@PathVariable String sessionCode) {
        try {
            preguntasIncomodasService.start(sessionCode);
            return ResponseEntity.ok(Map.of("message", "Preguntas Incómodas iniciado correctamente."));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/next-preguntas-incomodas")
    public ResponseEntity<?> getNextPreguntasIncomodas(@PathVariable String sessionCode, @RequestParam String tipo) {
        try {
            return ResponseEntity.ok(preguntasIncomodasService.getNext(sessionCode, tipo));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── El Impostor ──────────────────────────────────────────────────────────

    @PostMapping("/{sessionCode}/el-impostor/start")
    public ResponseEntity<?> startElImpostor(@PathVariable String sessionCode,
                                              @RequestBody Map<String, Object> body) {
        try {
            int impostorCount = ((Number) body.getOrDefault("impostorCount", 1)).intValue();
            elImpostorService.start(sessionCode, impostorCount);
            return ResponseEntity.ok(Map.of("message", "El Impostor iniciado"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionCode}/el-impostor/my-role")
    public ResponseEntity<?> getMyRole(@PathVariable String sessionCode,
                                        @RequestParam String username) {
        try {
            return ResponseEntity.ok(elImpostorService.getMyRole(sessionCode, username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/el-impostor/call-vote")
    public ResponseEntity<?> callVote(@PathVariable String sessionCode,
                                       @RequestParam String username) {
        try {
            elImpostorService.callVote(sessionCode, username);
            return ResponseEntity.ok(Map.of("message", "Votación iniciada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionCode}/el-impostor/vote")
    public ResponseEntity<?> submitVote(@PathVariable String sessionCode,
                                         @RequestBody Map<String, String> body) {
        String votingUser = body.get("votingUser");
        String votedUser = body.get("votedUser");
        if (votingUser == null || votingUser.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "votingUser es obligatorio"));
        if (votedUser == null || votedUser.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "votedUser es obligatorio"));
        try {
            elImpostorService.vote(sessionCode, votingUser, votedUser);
            return ResponseEntity.ok(Map.of("message", "Voto registrado"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Limpieza de estado en memoria al terminar un juego ──────────────────

    private void cleanupAllGameServices(String sessionCode) {
        yoNuncaNuncaService.cleanup(sessionCode);
        culturaPendejaService.cleanup(sessionCode);
        quienEsMasProbableService.cleanup(sessionCode);
        preguntasIncomodasService.cleanup(sessionCode);
        elImpostorService.cleanup(sessionCode);
    }
}
