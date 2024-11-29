package com.game;

import com.game.model.GameSession;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.QuestionRepository;
import com.game.repository.UserRepository;
import com.game.service.GameSessionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
public class GameSessionTests {

    @Autowired
    private GameSessionService gameSessionService;

    @MockBean
    private GameSessionRepository gameSessionRepository;

    @MockBean
    private UserRepository userRepository;

    @Test
    void testCreateGameSession() {
        User user = new User();
        user.setUsername("TestUser");

        // Configuración del usuario
        when(userRepository.findByUsername("TestUser")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(user);

        // Configuración de la sesión de juego
        GameSession gameSession = new GameSession();
        gameSession.setCreatorName("TestUser");
        gameSession.setSessionCode("1234");
        when(gameSessionRepository.saveAndFlush(any(GameSession.class))).thenReturn(gameSession);

        try {
            GameSession createdSession = gameSessionService.createGameSession(user);

            // Verifica que el objeto creado no sea nulo
            Assertions.assertNotNull(createdSession, "La sesión creada no debe ser nula");

            // Verifica las propiedades del objeto creado
            Assertions.assertEquals("1234", createdSession.getSessionCode());
            Assertions.assertEquals("TestUser", createdSession.getCreatorName());
        } catch (Exception e) {
            Assertions.fail("Exception occurred: " + e.getMessage());
        }
    }


    @Test
    void testGetUsersInSession() {
        String sessionCode = "1234";

        GameSession session = new GameSession();
        session.setSessionCode(sessionCode);

        List<User> users = new ArrayList<>();
        users.add(new User("User1"));
        users.add(new User("User2"));
        session.setUsers(users);

        when(gameSessionRepository.findBySessionCode(sessionCode)).thenReturn(Optional.of(session));

        try {
            List<User> userList = gameSessionService.getUsersInSession(sessionCode);
            Assertions.assertEquals(2, userList.size());
        } catch (Exception e) {
            Assertions.fail("Exception occurred: " + e.getMessage());
        }
    }

    @Test
    void testStartGame() {
        String sessionCode = "1234";

        GameSession session = new GameSession();
        session.setSessionCode(sessionCode);
        session.setQuestions(new ArrayList<>()); // Lista de preguntas
        session.setShownQuestions(new HashSet<>()); // Usar HashSet en lugar de ArrayList

        when(gameSessionRepository.findBySessionCode(sessionCode)).thenReturn(Optional.of(session));

        try {
            gameSessionService.startGame(sessionCode);
            Assertions.assertTrue(session.isGameStarted());
        } catch (Exception e) {
            Assertions.fail("Exception occurred: " + e.getMessage());
        }
    }

}
