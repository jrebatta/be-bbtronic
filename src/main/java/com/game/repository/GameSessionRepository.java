package com.game.repository;

import com.game.model.GameSession;
import com.game.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {
    // Define el método personalizado para encontrar una sesión por su código
    Optional<GameSession> findBySessionCode(String sessionCode);
    Optional<GameSession> findByUsersContaining(User user);
}
