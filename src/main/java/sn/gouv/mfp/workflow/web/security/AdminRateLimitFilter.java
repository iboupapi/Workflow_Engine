package sn.gouv.mfp.workflow.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limitation de débit des endpoints d'administration : fenêtre fixe
 * de 60 secondes, 120 requêtes maximum par adresse cliente, au-delà 429 explicite.
 * <p>
 * Mécanisme volontairement simple (compteurs en mémoire, pas de quota distribué) :
 * suffisant pour protéger l'import à chaud d'un poste unique ; un déploiement
 * multi-instances exigera un compteur partagé (Redis) — à réévaluer au dimensionnement.
 */
@Component
public class AdminRateLimitFilter extends OncePerRequestFilter {

    static final long WINDOW_SECONDS = 60;
    static final long MAX_REQUESTS_PER_WINDOW = 120;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientAddress(request);
        Window window = windows.computeIfAbsent(client, key -> new Window());
        if (!window.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"code\":\"RateLimitExceeded\",\"message\":\"Quota administrateur dépassé "
                            + "(120 requêtes/minute). Réessayez dans quelques secondes.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Compteur à fenêtre fixe, thread-safe par clé cliente. */
    private static final class Window {

        private long windowStart = Instant.now().getEpochSecond();
        private long count;

        synchronized boolean tryAcquire() {
            long now = Instant.now().getEpochSecond();
            if (now - windowStart >= WINDOW_SECONDS) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            count++;
            return true;
        }
    }
}
