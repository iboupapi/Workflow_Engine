package sn.gouv.mfp.workflow.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS de l'API, pour la console (Phase 2).
 * <p>
 * <strong>Refus par défaut</strong> : la propriété {@code app.cors.allowed-origins} est vide
 * hors profil {@code dev} — aucune origine cross-origin n'est autorisée. Le profil {@code dev}
 * la renseigne avec l'origine de la console Vite ({@code http://localhost:5173}).
 * <p>
 * Seules les méthodes de l'API et les en-têtes {@code Authorization}/{@code Content-Type}
 * sont autorisés : pas de cookie, pas de wildcard. L'authentification reste portée par le
 * jeton JWT de chaque requête.
 */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (origins.isEmpty()) {
            return source;
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
