package com.game.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // Broker para enviar mensajes
        config.setApplicationDestinationPrefixes("/app"); // Prefijo para mensajes entrantes
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/websocket") // Endpoint del WebSocket
                .setAllowedOrigins("https://fe-bbtronic.vercel.app", "http://127.0.0.1:5500") // Permitir solicitudes desde el origen del frontend
                .withSockJS(); // Habilitar SockJS
    }
}
