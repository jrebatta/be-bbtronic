package com.game.repository;

import com.game.model.ImpostorWord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImpostorWordRepository extends JpaRepository<ImpostorWord, Long> {
}
