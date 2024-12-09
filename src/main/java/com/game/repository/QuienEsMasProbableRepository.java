package com.game.repository;

import com.game.model.QuienEsMasProbable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuienEsMasProbableRepository extends JpaRepository<QuienEsMasProbable, Long> {
    List<QuienEsMasProbable> findByTipo(String tipo);
}
