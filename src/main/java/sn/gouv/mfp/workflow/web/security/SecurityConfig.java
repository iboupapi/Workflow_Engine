package sn.gouv.mfp.workflow.web.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;

/**
 * Chaîne de sécurité de l'API : refus par défaut (cahier de recette, retour de relecture).
 * <p>
 * Seule la documentation reste publique (Swagger UI et spécification OpenAPI). Tout le
 * reste de l'API exige une identité authentifiée : JWT d'un émetteur configuré. Sans
 * identifiants : 401 ; identifié sans le droit : 403. Aucun nouvel endpoint n'est
 * public par oubli.
 * <p>
 * L'administration ({@code /api/admin/**}) exige le rôle {@code ADMIN}, accordé uniquement
 * à un <strong>administrateur nommé</strong> (sujet du jeton déclaré dans
 * {@code auth.admin.subjects}) — le jeton fixe intérimaire a été retiré.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            AdminRateLimitFilter adminRateLimitFilter,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver)
            throws Exception {
        BearerTokenAuthenticationEntryPoint entryPoint =
                new BearerTokenAuthenticationEntryPoint();
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/actuator/health")
                        .permitAll()
                        // Outils de développement (profil dev uniquement) : l'endpoint de
                        // génération de jetons doit être atteignable SANS jeton, sinon il ne
                        // sert à rien. Le contrôleur n'existe pas hors profil dev : en
                        // production, ces chemins ne trouvent aucun handler (404).
                        .requestMatchers("/api/dev/**")
                        .permitAll()
                        // Le dispatch d'erreur interne doit rester joignable, sinon toute
                        // erreur métier (400/404/409) serait masquée en 403.
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(jwtAuthenticationManagerResolver)
                        .authenticationEntryPoint(entryPoint))
                .addFilterBefore(adminRateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Même garde que ci-dessus : un seul passage, dans la chaîne de sécurité.
     */
    @Bean
    FilterRegistrationBean<AdminRateLimitFilter> adminRateLimitFilterRegistration(
            AdminRateLimitFilter filter) {
        FilterRegistrationBean<AdminRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
