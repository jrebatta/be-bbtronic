package com.game.controller;

import com.game.model.QuienEsMasProbable;
import com.game.service.QuienEsMasProbableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quien-es-mas-probable")
public class QuienEsMasProbableController {

    @Autowired
    private QuienEsMasProbableService service;

    @GetMapping("/random")
    public ResponseEntity<?> getRandomQuestion(@RequestParam("tipo") String tipo) {
        try {
            QuienEsMasProbable question = service.getRandomQuestion(tipo);
            return ResponseEntity.ok(question);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
