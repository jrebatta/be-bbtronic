package com.game.service;

import com.game.model.WaitingMessage;
import com.game.repository.WaitingMessageRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WaitingMessageSeeder {

    private final WaitingMessageRepository waitingMessageRepository;

    public WaitingMessageSeeder(WaitingMessageRepository waitingMessageRepository) {
        this.waitingMessageRepository = waitingMessageRepository;
    }

    @PostConstruct
    public void seed() {
        if (waitingMessageRepository.count() == 0) {
            waitingMessageRepository.saveAll(List.of(
                new WaitingMessage("Hasta que hora te esperamos {usuarios}...."),
                new WaitingMessage("Tengo toda la noche {usuarios}..."),
                new WaitingMessage("{usuarios} avisan")
            ));
        }
    }
}
