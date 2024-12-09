package com.game.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.model.GameSession;
import com.game.model.Question;
import com.game.model.User;
import com.game.service.GameSessionService;
import com.game.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = {"https://fe-bbtronic.vercel.app", "http://127.0.0.1:5500"}) // Permite solicitudes solo desde este origen
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
            // Registrar al usuario o recuperarlo si ya existe
            User user = userService.registerUser(username);

            // Crear la sesión de juego y asociarla al usuario
            GameSession gameSession = gameSessionService.createGameSession(user);

            // Construir la respuesta con los datos generados
            Map<String, String> response = new HashMap<>();
            response.put("username", user.getUsername());
            response.put("sessionToken", user.getSessionToken());
            response.put("sessionCode", gameSession.getSessionCode());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Manejar errores específicos como usuario ya existente o sesión no válida
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        } catch (Exception e) {
            // Manejar errores generales
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Ocurrió un error inesperado: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }


    @GetMapping("/{sessionCode}")
    public ResponseEntity<?> getGameSession(@PathVariable("sessionCode") String sessionCode) {
        try {
            GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
            List<User> users = gameSessionService.getUsersInSession(sessionCode); // Obtener usuarios de la sesión

            Map<String, Object> response = new HashMap<>();
            response.put("creator", session.getCreatorName());
            response.put("gameStarted", session.isGameStarted());
            response.put("users", users); // Agregar lista de usuarios a la respuesta

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Código de sesión inválido."));
        }
    }


    // Endpoint para unirse a una sesión existente
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
            // Registrar usuario y agregarlo a la sesión
            User user = userService.registerUser(username);
            gameSessionService.addUserToSession(sessionCode, user);

            // Obtener la lista actualizada de usuarios
            List<User> updatedUsers = gameSessionService.getUsersInSession(sessionCode);

            // Enviar evento a través del WebSocket para notificar a todos los usuarios
            Map<String, Object> message = new HashMap<>();
            message.put("event", "userUpdate");
            message.put("users", updatedUsers);
            messagingTemplate.convertAndSend("/topic/" + sessionCode, message);

            // Respuesta para el usuario que se une
            return ResponseEntity.ok(Map.of("sessionToken", user.getSessionToken()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
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
    public ResponseEntity<Map<String, String>> startGame(@PathVariable String sessionCode) {
        gameSessionService.startGame(sessionCode);

        // Notificar a todos los usuarios que el juego ha comenzado
        messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"gameStarted\"}");

        // Crear el objeto de respuesta JSON
        Map<String, String> response = new HashMap<>();
        response.put("mensaje", "Juego iniciado");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionCode}/check-all-ready")
    public ResponseEntity<Map<String, Object>> checkAllReady(@PathVariable("sessionCode") String sessionCode) {
        GameSession session = gameSessionService.getGameSessionByCode(sessionCode);
        boolean allReady = session.getUsers().stream().allMatch(User::isReady);

        if (allReady) {
            System.out.println("Todos los usuarios están listos. Enviando evento allReady...");
            messagingTemplate.convertAndSend("/topic/" + sessionCode, "{\"event\":\"allReady\"}");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("allReady", allReady);
        response.put("message", allReady ? "Todos los usuarios están listos" : "Aún faltan usuarios por estar listos");

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> generateQuestionResponse(Question question, int currentQuestionNumber, int totalQuestions, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("question", question);
        response.put("numeroDePregunta", currentQuestionNumber + "/" + totalQuestions);
        if (message != null && !message.isEmpty()) {
            response.put("message", message);
        }
        return response;
    }


    @GetMapping("/{sessionCode}/current-question")
    public ResponseEntity<Map<String, Object>> getCurrentQuestion(@PathVariable("sessionCode") String sessionCode) {
        try {
            Question currentQuestion = gameSessionService.getCurrentQuestion(sessionCode);
            int totalQuestions = gameSessionService.getTotalQuestions(sessionCode);
            int currentQuestionNumber = gameSessionService.getCurrentQuestionNumber(sessionCode);

            Map<String, Object> response = generateQuestionResponse(currentQuestion, currentQuestionNumber, totalQuestions, null);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }


    @PostMapping("/{sessionCode}/next-random-question")
    public ResponseEntity<Map<String, Object>> nextRandomQuestion(
            @PathVariable("sessionCode") String sessionCode,
            @RequestBody Map<String, String> requestBody) {

        if (requestBody == null || !requestBody.containsKey("lastToUser") || requestBody.get("lastToUser").isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String lastToUser = requestBody.get("lastToUser");

        try {
            Question nextQuestion = gameSessionService.selectAndSetNextQuestion(sessionCode, lastToUser);
            int totalQuestions = gameSessionService.getTotalQuestions(sessionCode);
            int currentQuestionNumber = gameSessionService.getCurrentQuestionNumber(sessionCode);

            Map<String, Object> response = generateQuestionResponse(nextQuestion, currentQuestionNumber, totalQuestions, "Pregunta siguiente seleccionada");

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Se mostraron todas las preguntas");
            response.put("allQuestionsShown", true);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).build();
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetGameData(@RequestBody Map<String, String> requestBody) {
        try {
            // Obtener el código de sesión del cuerpo de la solicitud
            String sessionCode = requestBody.get("sessionCode");
            if (sessionCode == null || sessionCode.isEmpty()) {
                return ResponseEntity.badRequest().body("El código de sesión es obligatorio.");
            }

            // Llamar al servicio para resetear los datos de la sesión
            gameSessionService.resetGameData(sessionCode);
            return ResponseEntity.ok("Los datos de la sesión con código " + sessionCode + " se han reiniciado correctamente.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("No se pudieron reiniciar los datos del juego: " + e.getMessage());
        }
    }
}
