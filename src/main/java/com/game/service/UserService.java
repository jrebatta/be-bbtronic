package com.game.service;

import com.game.model.User;
import com.game.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    @Autowired
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        Optional<User> user = userRepository.findBySessionToken(sessionToken);
        if (user.isPresent()) {
            userRepository.delete(user.get());
            return true;
        }
        return false;
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
