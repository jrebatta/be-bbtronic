package com.game.service;

import com.game.model.GameSession;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final GameSessionRepository gameSessionRepository;

    @Autowired
    public UserService(UserRepository userRepository, GameSessionRepository gameSessionRepository) {
        this.userRepository = userRepository;
        this.gameSessionRepository = gameSessionRepository;
    }

    /**
     * Registra un nuevo usuario si el nombre de usuario no está ya en uso.
     *
     * @param username el nombre de usuario del nuevo usuario
     * @return el usuario registrado
     * @throws IllegalArgumentException si el nombre de usuario ya está en uso
     */
    public User registerUser(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso.");
        });
        User user = new User(username);
        return userRepository.save(user);
    }


    /**
     * Permite a un usuario unirse a una sesión ya creada.
     *
     * @param sessionCode el código de la sesión
     * @param username el nombre de usuario del usuario
     * @throws IllegalArgumentException si la sesión no es encontrada o el usuario ya está en la sesión
     */
    public void joinSession(String sessionCode, String username) {
        GameSession session = gameSessionRepository.findBySessionCode(sessionCode)
                .orElseThrow(() -> new IllegalArgumentException("Código de sesión inválido"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (session.getUsers().stream().anyMatch(u -> u.getUsername().equals(username))) {
            throw new IllegalArgumentException("El usuario ya está en la sesión");
        }

        user.setGameSession(session);
        session.addUser(user);

        gameSessionRepository.save(session);
        userRepository.save(user);
    }

    /**
     * Recupera todos los usuarios.
     *
     * @return una lista de todos los usuarios
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Cierra la sesión de un usuario y lo elimina de la base de datos.
     *
     * @param sessionToken el token de sesión del usuario
     * @return true si el usuario fue cerrado correctamente, false en caso contrario
     */
    public boolean logoutUser(String sessionToken) {
        Optional<User> user = userRepository.findBySessionToken(sessionToken);

        if (user.isPresent()) {
            User foundUser = user.get();
            removeUserFromSession(foundUser);
            userRepository.delete(foundUser);
            return true;
        }

        return false;
    }

    /**
     * Elimina a un usuario de cualquier sesión de juego en la que esté.
     *
     * @param user el usuario a ser eliminado
     */
    private void removeUserFromSession(User user) {
        gameSessionRepository.findByUsersContaining(user).ifPresent(session -> {
            session.getUsers().remove(user);
            gameSessionRepository.save(session);
        });
    }

    /**
     * Borra todos los usuarios al iniciar la aplicación.
     */
    @PostConstruct
    public void clearUsersOnStartup() {
        userRepository.deleteAll();
        System.out.println("Todos los usuarios han sido borrados al iniciar la aplicación.");
    }

    /**
     * Marca a un usuario como listo.
     *
     * @param username el nombre de usuario del usuario
     * @throws IllegalArgumentException si el usuario no es encontrado
     */
    public void setUserReady(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        user.setReady(true);
        userRepository.save(user);
    }

    /**
     * Marca a un usuario como listo y devuelve información de la ronda actual.
     *
     * @param username el nombre de usuario del usuario
     * @return Map con información de la ronda y estado del usuario
     * @throws IllegalArgumentException si el usuario no es encontrado
     */
    public Map<String, Object> setUserReadyWithRoundInfo(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        user.setReady(true);
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("username", username);
        response.put("ready", true);

        // Agregar información de la sesión y ronda si el usuario está en una sesión
        if (user.getGameSession() != null) {
            GameSession session = user.getGameSession();
            response.put("sessionCode", session.getSessionCode());
            response.put("roundId", session.getCurrentRoundId());
            response.put("roundStatus", session.getRoundStatus());

            // Contar usuarios listos
            long readyCount = session.getUsers().stream().filter(User::isReady).count();
            response.put("usersReady", readyCount);
            response.put("totalUsers", session.getUsers().size());
            response.put("allUsersReady", readyCount == session.getUsers().size());
        }

        return response;
    }
}

