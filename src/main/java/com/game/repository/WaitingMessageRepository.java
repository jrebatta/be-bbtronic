package com.game.repository;

import com.game.model.WaitingMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WaitingMessageRepository extends JpaRepository<WaitingMessage, Long> {
}
