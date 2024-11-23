package com.game.repository;

import com.game.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Método para encontrar usuario por nombre de usuario
    Optional<User> findByUsername(String username);

    // Método para encontrar usuario por token de sesión
    Optional<User> findBySessionToken(String sessionToken);
}
