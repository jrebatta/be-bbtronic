package com.game.service;

import com.game.model.CulturaPendeja;
import com.game.repository.CulturaPendejaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class CulturaPendejaService {

    @Autowired
    private CulturaPendejaRepository repository;

    private List<CulturaPendeja> currentSessionQuestions = new ArrayList<>();

    public CulturaPendeja getRandomQuestion(String tipo) {
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
