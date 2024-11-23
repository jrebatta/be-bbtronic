package com.game.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.service.GameSessionService;
import com.game.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "https://be-bbtronic.onrender.com") // Permite solicitudes solo desde este origen
@RequestMapping("/api/game-sessions")
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    public GameSessionController(GameSessionService gameSessionService, UserService userService, SimpMessagingTemplate messagingTemplate) {
        this.gameSessionService = gameSessionService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> createGameSession(@RequestParam("username") String username) {
        try {
            User user = userService.registerUser(username);
            GameSession gameSession = gameSessionService.createGameSession(user);

            Map<String, String> response = new HashMap<>();
            response.put("username", user.getUsername());
            response.put("sessionToken", user.getSessionToken());
            response.put("sessionCode", gameSession.getSessionCode());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        }
    }

    @MessageMapping("/updateQuestion/{sessionCode}")
    public void updateQuestion(@DestinationVariable String sessionCode) {
        // Enviar mensaje de actualización a todos los suscriptores
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "update");
    }

    @GetMapping("/{sessionCode}")
    public ResponseEntity<?> getGameSession(@PathVariable("sessionCode") String sessionCode) {
        try {
            GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
            Map<String, Object> response = new HashMap<>();
            response.put("creator", session.getCreatorName());
            response.put("gameStarted", session.isGameStarted());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Código de sesión inválido");
        }
    }

    // Endpoint para unirse a una sesión existente
    @PostMapping("/join")
    public ResponseEntity<Map<String, String>> joinGameSession(@RequestBody Map<String, String> requestData) {
        String sessionCode = requestData.get("sessionCode");
        String username = requestData.get("username");

        try {
            User user = userService.registerUser(username);
            gameSessionService.addUserToSession(sessionCode, user);

            // Obtener la lista completa de usuarios actualizada
            List<User> updatedUsers = gameSessionService.getUsersInSession(sessionCode);

            // Crear un mensaje JSON para enviar a los clientes
            Map<String, Object> message = new HashMap<>();
            message.put("event", "userUpdate");
            message.put("users", updatedUsers);

            // Enviar el mensaje a todos los suscriptores
            messagingTemplate.convertAndSend("/topic/" + sessionCode, message);

            Map<String, String> response = new HashMap<>();
            response.put("sessionToken", user.getSessionToken());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(null);
        }
    }

    @GetMapping("/{sessionCode}/users")
    public ResponseEntity<?> getUsersInSession(@PathVariable("sessionCode") String sessionCode) {
        try {
            GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
            List<User> users = gameSessionService.getUsersInSession(sessionCode);

            Map<String, Object> response = new HashMap<>();
            response.put("creator", session.getCreatorName());
            response.put("users", users);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }

    @PostMapping("/{sessionCode}/send-question")
    public ResponseEntity<?> sendQuestion(
            @PathVariable("sessionCode") String sessionCode,
            @RequestBody Map<String, Object> requestData) {

        String fromUser = (String) requestData.get("fromUser");
        String toUser = (String) requestData.get("toUser");
        String questionText = (String) requestData.get("question");
        boolean anonymous = (Boolean) requestData.get("anonymous");

        gameSessionService.saveQuestion(sessionCode, fromUser, toUser, questionText, anonymous);

        return ResponseEntity.ok().build();
    }

    // Endpoint para iniciar el juego
    @PostMapping("/{sessionCode}/start-game")
    public ResponseEntity<Void> startGame(@PathVariable String sessionCode) {
        gameSessionService.startGame(sessionCode);

        // Notificar a todos los usuarios que el juego ha comenzado
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"gameStarted\"}");

        return ResponseEntity.ok().build();
    }

    @GetMapping("/{sessionCode}/check-all-ready")
    public ResponseEntity<Map<String, Boolean>> checkAllReady(@PathVariable("sessionCode") String sessionCode) {
        GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
        boolean allReady = session.getUsers().stream().allMatch(user -> user.isReady());

        if (allReady) {
            System.out.println("Todos los usuarios están listos. Enviando evento allReady...");
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "allReady");
        }

        Map<String, Boolean> response = new HashMap<>();
        response.put("allReady", allReady);
        return ResponseEntity.ok(response);
    }




    @PostMapping("/{sessionCode}/next-random-question")
    public ResponseEntity<Question> nextRandomQuestion(
            @PathVariable("sessionCode") String sessionCode,
            @RequestParam("lastToUser") String lastToUser) {

        if (lastToUser == null || lastToUser.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Question nextQuestion = gameSessionService.selectAndSetNextQuestion(sessionCode, lastToUser);

            // Enviar evento a todos los clientes conectados
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"update\"}");

            return ResponseEntity.ok(nextQuestion);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).build();
        }
    }

    @GetMapping("/{sessionCode}/current-question")
    public ResponseEntity<Question> getCurrentQuestion(@PathVariable("sessionCode") String sessionCode) {
        GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
        List<Question> orderedQuestions = gameSessionService.getQuestionsForSession(sessionCode);
        int currentIndex = session.getCurrentQuestionIndex();

        if (currentIndex < 0 || currentIndex >= orderedQuestions.size()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        Question currentQuestion = orderedQuestions.get(currentIndex);
        return ResponseEntity.ok(currentQuestion);
    }
}
