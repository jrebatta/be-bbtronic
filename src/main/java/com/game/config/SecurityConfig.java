package com.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable() // Deshabilitar CSRF
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/websocket/**").permitAll() // Permitir solicitudes a WebSocket
                        .requestMatchers("/api/users/**").permitAll() // Permite acceso sin autenticación
                        .requestMatchers("/api/game-sessions/**").permitAll() // Permite acceso sin autenticación
                        .anyRequest().permitAll() // Permitir todas las solicitudes sin autenticación
                )
                .cors(); // Habilitar CORS

        return http.build();
    }
}
