package com.game.service;

import com.game.model.GameSession;
import com.game.model.User;
import com.game.repository.GameSessionRepository;
import com.game.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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

    // Método para registrar usuario, validando si ya existe
    public User registerUser(String username) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso.");
        }
        User user = new User(username);
        return userRepository.save(user);
    }

    // Método para obtener todos los usuarios (en línea)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // Método para cerrar la sesión del usuario y eliminarlo de la base de datos
    public boolean logoutUser(String sessionToken) {
        // Buscar al usuario por su sessionToken
        Optional<User> user = userRepository.findBySessionToken(sessionToken);

        if (user.isPresent()) {
            User foundUser = user.get();

            // Eliminar el usuario de cualquier sesión de juego si está presente
            Optional<GameSession> gameSession = gameSessionRepository.findByUsersContaining(foundUser);
            gameSession.ifPresent(session -> {
                session.getUsers().remove(foundUser); // Eliminar el usuario de la sesión
                gameSessionRepository.save(session); // Guardar los cambios en la sesión
            });

            // Eliminar el usuario de la tabla de usuarios
            userRepository.delete(foundUser);

            return true; // Operación exitosa
        }

        return false; // Usuario no encontrado
    }


    // Método que borra todos los usuarios al iniciar la aplicación
    @PostConstruct
    public void clearUsersOnStartup() {
        userRepository.deleteAll();
        System.out.println("Todos los usuarios han sido borrados al iniciar la aplicación.");
    }

    public void setUserReady(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        user.setReady(true);
        userRepository.save(user);
    }

}
