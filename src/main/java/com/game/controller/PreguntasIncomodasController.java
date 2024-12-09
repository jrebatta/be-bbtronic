package com.game.controller;

import com.game.model.PreguntasIncomodas;
import com.game.service.PreguntasIncomodasService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"https://fe-bbtronic.vercel.app", "http://127.0.0.1:5500"}) // Permite solicitudes solo desde este origen
@RequestMapping("/api/preguntas-incomodas")
public class PreguntasIncomodasController {

    @Autowired
    private PreguntasIncomodasService service;

    @GetMapping("/random")
    public ResponseEntity<?> getRandomQuestion(@RequestParam("tipo") String tipo) {
        try {
            PreguntasIncomodas question = service.getRandomQuestion(tipo);
            return ResponseEntity.ok(question);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
