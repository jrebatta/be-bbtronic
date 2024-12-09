package com.game.repository;

import com.game.model.PreguntasIncomodas;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PreguntasIncomodasRepository extends JpaRepository<PreguntasIncomodas, Long> {
    List<PreguntasIncomodas> findByTipo(String tipo);
}
