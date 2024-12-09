package com.game.service;

import com.game.model.QuienEsMasProbable;
import com.game.repository.QuienEsMasProbableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class QuienEsMasProbableService {

    @Autowired
    private QuienEsMasProbableRepository repository;

    private List<QuienEsMasProbable> currentSessionQuestions = new ArrayList<>();

    public QuienEsMasProbable getRandomQuestion(String tipo) {
        // Cargar preguntas en la sesión si no hay preguntas disponibles
        if (currentSessionQuestions.isEmpty()) {
            currentSessionQuestions = repository.findByTipo(tipo);
            Collections.shuffle(currentSessionQuestions); // Barajar las preguntas
        }

        if (!currentSessionQuestions.isEmpty()) {
            return currentSessionQuestions.remove(0); // Retornar y eliminar la primera pregunta
        } else {
            throw new IllegalStateException("No hay más preguntas disponibles para el tipo: " + tipo);
        }
    }
}
