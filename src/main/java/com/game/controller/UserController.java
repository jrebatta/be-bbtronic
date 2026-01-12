package com.game.controller;

import com.game.model.User;
import com.game.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = {"https://fe-bbtronic-vue.vercel.app", "http://127.0.0.1:5500"}) // Permite solicitudes solo desde este origen
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Obtiene todos los usuarios.
     *
     * @return una lista de todos los usuarios
     */
    @GetMapping("/all")
    public ResponseEntity<List<User>> getAllUsers() {
        List<User> allUsers = userService.getAllUsers();
        return ResponseEntity.ok(allUsers);
    }

    /**
     * Registra un nuevo usuario.
     *
     * @param username el nombre de usuario del nuevo usuario
     * @return una respuesta con el nombre de usuario y el token de sesión
     */
    @GetMapping("/register")
    public ResponseEntity<Map<String, String>> registerUser(@RequestParam("username") String username) {
        try {
            User user = userService.registerUser(username);
            Map<String, String> response = new HashMap<>();
            response.put("username", user.getUsername());
            response.put("sessionToken", user.getSessionToken());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        }
    }

    /**
     * Obtiene los usuarios en línea.
     *
     * @return una lista de usuarios en línea
     */
    @GetMapping("/online")
    public ResponseEntity<List<Map<String, String>>> getOnlineUsers() {
        List<User> onlineUsers = userService.getAllUsers();
        List<Map<String, String>> response = new ArrayList<>();

        for (User user : onlineUsers) {
            Map<String, String> userMap = new HashMap<>();
            userMap.put("username", user.getUsername());
            response.add(userMap);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Cierra la sesión de un usuario.
     *
     * @param sessionToken el token de sesión del usuario
     * @return una respuesta indicando el resultado de la operación
     */
    @DeleteMapping("/logout")
    public ResponseEntity<String> logoutUser(@RequestParam("sessionToken") String sessionToken) {
        boolean isLoggedOut = userService.logoutUser(sessionToken);

        if (isLoggedOut) {
            return ResponseEntity.ok("Sesión cerrada exitosamente.");
        } else {
            return ResponseEntity.ok("Usuario no encontrado. Redirigiendo a login."); // Estado OK y mensaje específico
        }
    }

    /**
     * Marca a un usuario como listo.
     *
     * @param username el nombre de usuario del usuario
     * @return una respuesta indicando el resultado de la operación
     */
    @PostMapping("/{username}/ready")
    public ResponseEntity<Void> setUserReady(@PathVariable String username) {
        userService.setUserReady(username);
        System.out.println("Usuario marcado como listo: " + username);
        return ResponseEntity.ok().build();
    }
}