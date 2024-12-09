package com.game.controller;

import com.game.model.CulturaPendeja;
import com.game.service.CulturaPendejaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cultura-pendeja")
public class CulturaPendejaController {

    @Autowired
    private CulturaPendejaService service;

    @GetMapping("/random")
    public ResponseEntity<?> getRandomQuestion(@RequestParam("tipo") String tipo) {
        try {
            CulturaPendeja question = service.getRandomQuestion(tipo);
            return ResponseEntity.ok(question);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
