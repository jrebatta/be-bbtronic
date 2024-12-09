package com.game.repository;

import com.game.model.YoNuncaNunca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface YoNuncaNuncaRepository extends JpaRepository<YoNuncaNunca, Long> {
    List<YoNuncaNunca> findByTipo(String tipo);

}
