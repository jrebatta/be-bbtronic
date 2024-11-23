//package com.game.config;
//
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.util.UUID;
//
//public class TokenAuthenticationFilter extends OncePerRequestFilter {
//
//    // Método estático para generar un token único
//    public static String generateToken() {
//        return UUID.randomUUID().toString(); // Genera un token único usando UUID
//    }
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
//            throws ServletException, IOException {
//
//        // Agregar los encabezados de CORS a la respuesta
//        response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5500"); // Origen permitido
//        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE"); // Métodos permitidos
//        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization"); // Encabezados permitidos
//        response.setHeader("Access-Control-Allow-Credentials", "true"); // Permitir credenciales (cookies)
//
//        // Si es una solicitud de preflight, responder rápidamente
//        if ("OPTIONS".equals(request.getMethod())) {
//            response.setStatus(HttpServletResponse.SC_OK);
//            return;
//        }
//
//        filterChain.doFilter(request, response);
//    }
//}
