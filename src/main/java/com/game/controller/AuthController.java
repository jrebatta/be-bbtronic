package com.game.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = "http://127.0.0.1:5500")  // Permite CORS solo para este controlador
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/token")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        // Simplemente retornar un mensaje de éxito sin validar credenciales ni generar token
        return ResponseEntity.ok("Login successful");
    }
}
