package com.game.service;

import com.game.model.PreguntasIncomodas;
import com.game.repository.PreguntasIncomodasRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PreguntasIncomodasService {

    @Autowired
    private PreguntasIncomodasRepository repository;

    private List<PreguntasIncomodas> currentSessionQuestions = new ArrayList<>();

    public PreguntasIncomodas getRandomQuestion(String tipo) {
        // Cargar preguntas en la sesión si no hay preguntas disponibles
        if (currentSessionQuestions.isEmpty()) {
            currentSessionQuestions = repository.findByTipo(tipo);
            Collections.shuffle(currentSessionQuestions); // Barajar las preguntas
        }

        if (!currentSessionQuestions.isEmpty()) {
            return currentSessionQuestions.removeFirst(); // Retornar y eliminar la primera pregunta
        } else {
            throw new IllegalStateException("No hay más preguntas disponibles para el tipo: " + tipo);
        }
    }
}
