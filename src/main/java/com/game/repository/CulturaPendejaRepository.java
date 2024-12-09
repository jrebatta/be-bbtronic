package com.game.repository;

import com.game.model.CulturaPendeja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CulturaPendejaRepository extends JpaRepository<CulturaPendeja, Long> {
    List<CulturaPendeja> findByTipo(String tipo);
}
