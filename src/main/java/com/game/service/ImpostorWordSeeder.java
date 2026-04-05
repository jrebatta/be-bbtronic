package com.game.service;

import com.game.model.ImpostorWord;
import com.game.repository.ImpostorWordRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ImpostorWordSeeder {

    private final ImpostorWordRepository repository;

    public ImpostorWordSeeder(ImpostorWordRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seed() {
        if (repository.count() > 0) return;

        List<ImpostorWord> words = new ArrayList<>();

        add(words, "animales",
                "gato", "perro", "elefante", "tigre", "jirafa", "cocodrilo", "delfín",
                "loro", "pingüino", "hormiga", "lobo", "zorro", "caballo", "panda",
                "koala", "iguana", "cangrejo", "medusa", "pulpo", "murciélago",
                "ballena", "flamenco", "avestruz", "rinoceronte", "chimpancé");

        add(words, "comida",
                "pizza", "hamburguesa", "sushi", "tacos", "pasta", "ensalada",
                "chocolate", "helado", "pollo", "sandía", "mango", "sopa", "ceviche",
                "empanada", "arepa", "paella", "ramen", "burrito", "waffles", "churros",
                "tiramisu", "croquetas", "gazpacho", "tamales", "brigadeiro");

        add(words, "deportes",
                "fútbol", "baloncesto", "tenis", "natación", "ciclismo", "boxeo",
                "esquí", "voleibol", "golf", "béisbol", "surf", "judo", "escalada",
                "patinaje", "ajedrez", "polo", "rugby", "kárate", "triatlón", "esgrima",
                "curling", "críquet", "lacrosse", "halterofilia", "pentatlón");

        add(words, "objetos",
                "tijeras", "martillo", "paraguas", "linterna", "brújula", "termómetro",
                "calculadora", "microscopio", "telescopio", "lupa", "cerradura", "cojín",
                "espejo", "botella", "mochila", "ventilador", "maletín", "candado",
                "auriculares", "perchero", "sacacorchos", "abrelatas", "embudo",
                "bisturí", "compás");

        add(words, "lugares",
                "aeropuerto", "mercado", "hospital", "estadio", "biblioteca", "zoológico",
                "faro", "monasterio", "volcán", "cueva", "desierto", "isla", "glaciar",
                "catedral", "acuario", "casino", "observatorio", "granja", "puerto",
                "cementerio", "laberinto", "catacumba", "invernadero", "embajada", "fiordo");

        add(words, "peliculas",
                "Titanic", "Matrix", "Inception", "Shrek", "Coco", "Rocky", "Alien",
                "Gladiator", "Joker", "Interstellar", "Frozen", "Moana", "Encanto",
                "Parasite", "Avatar", "Jaws", "Psycho", "Grease", "Braveheart",
                "Scarface", "Beetlejuice", "Casablanca", "Spotlight", "Boyhood", "Arrival");

        repository.saveAll(words);
    }

    private void add(List<ImpostorWord> list, String category, String... wordArray) {
        for (String w : wordArray) {
            ImpostorWord iw = new ImpostorWord();
            iw.setWord(w);
            iw.setCategory(category);
            list.add(iw);
        }
    }
}
