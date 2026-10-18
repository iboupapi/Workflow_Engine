package sn.gouv.mfp.workflow.web.security;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Validation multi-émetteurs des jetons JWT (cahier de recette 2.2 à 2.4 : signature,
 * émetteur, audience et expiration vérifiés — sinon 401).
 * <p>
 * L'émetteur est lu SANS vérification dans la revendication {@code iss}, puis le jeton
 * est vérifié contre la configuration de CET émetteur. Émetteur inconnu ou jeton
 * illisible : 401, sans accès au moteur.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AdminProperties.class})
public class JwtAuthConfig {

    private final BearerTokenResolver bearerResolver = new DefaultBearerTokenResolver();

    /**
     * Résout le gestionnaire d'authentification selon l'émetteur déclaré du jeton.
     * Carte vide = aucun jeton accepté.
     */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver(
            JwtProperties properties, AdminProperties admins) {
        Map<String, AuthenticationManager> managers = new LinkedHashMap<>();
        if (properties.issuers() != null) {
            properties.issuers().forEach((name, issuer) ->
                    managers.put(issuer.issuer(), authenticationManager(name, issuer, admins)));
        }
        return request -> {
            String token = bearerResolver.resolve(request);
            String issuer;
            try {
                issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                throw new InvalidBearerTokenException("Jeton illisible.");
            }
            AuthenticationManager manager = managers.get(issuer);
            if (manager == null) {
                throw new InvalidBearerTokenException(
                        "Émetteur '" + issuer + "' non reconnu.");
            }
            return manager;
        };
    }

    private AuthenticationManager authenticationManager(
            String name, JwtProperties.IssuerProperties issuer, AdminProperties admins) {
        NimbusJwtDecoder decoder = decoder(name, issuer);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(issuer.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), audience));
        String rolesClaim = issuer.rolesClaim() != null && !issuer.rolesClaim().isBlank()
                ? issuer.rolesClaim()
                : "roles";
        // L'acteur porte une identité PRÉFIXÉE par le nom (clé de configuration) de l'émetteur :
        // « nomEmetteur:sub » (SEC-03). Deux émetteurs aux sub identiques ne peuvent donc pas
        // se confondre, et le contrôle d'administrateur compare l'identité complète (émetteur inclus).
        Converter<Jwt, AbstractAuthenticationToken> converter = jwt -> new JwtAuthenticationToken(
                jwt,
                authorities(jwt, name, rolesClaim, issuer.roleMapping(), admins),
                identity(name, jwt.getSubject()));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }

    /**
     * Identité certifiée de l'acteur : {@code nomEmetteur:sub} (le nom est la clé de
     * configuration de l'émetteur, ex. {@code agents}, {@code usagers}). Le séparateur est
     * le premier {@code :} ; un nom d'émetteur ne doit donc pas contenir {@code :}.
     */
    static String identity(String issuerName, String subject) {
        return subject == null || subject.isBlank() ? issuerName : issuerName + ":" + subject;
    }

    /**
     * Autorités Spring Security accordées à un jeton :
     * <ul>
     *   <li>rôles <strong>moteur</strong> : traduits depuis les rôles du jeton par la
     *   correspondance de l'émetteur (ou identité si aucune correspondance) — un rôle
     *   du jeton non reconnu n'accorde rien ;</li>
     *   <li>{@code ROLE_ADMIN} : uniquement si l'identité complète ({@code nomEmetteur:sub})
     *   figure dans {@code auth.admin.subjects} — l'émetteur est donc vérifié aussi.</li>
     * </ul>
     */
    private List<GrantedAuthority> authorities(
            Jwt jwt,
            String issuerName,
            String rolesClaim,
            Map<String, List<String>> roleMapping,
            AdminProperties admins) {
        Set<String> engineRoles = engineRoles(jwt.getClaimAsStringList(rolesClaim), roleMapping);
        List<GrantedAuthority> authorities = new ArrayList<>();
        engineRoles.stream()
                .filter(role -> role != null && !role.isBlank())
                .forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        if (admins.subjectsOrEmpty().contains(identity(issuerName, jwt.getSubject()))) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }

    /**
     * Rôles moteur dérivés des rôles du jeton par la correspondance de l'émetteur.
     * <p>
     * Défaut sûr : <strong>sans correspondance configurée, aucun rôle n'est accordé</strong>
     * — jamais l'identité du jeton. Un rôle moteur est retenu dès que le jeton porte l'un de
     * ses rôles acceptés ; un rôle du jeton non reconnu ne donne rien. Le rôle réservé
     * {@code ADMIN} n'est jamais accordé depuis le jeton (uniquement aux sujets nommés).
     */
    static Set<String> engineRoles(
            List<String> tokenRoles, Map<String, List<String>> roleMapping) {
        Set<String> engineRoles = new LinkedHashSet<>();
        if (tokenRoles == null || roleMapping == null || roleMapping.isEmpty()) {
            return engineRoles;
        }
        roleMapping.forEach((engineRole, accepted) -> {
            if (engineRole == null || engineRole.isBlank()
                    || AdminProperties.ADMIN_ROLE.equalsIgnoreCase(engineRole.trim())) {
                return;
            }
            if (accepted != null && accepted.stream().anyMatch(tokenRoles::contains)) {
                engineRoles.add(engineRole);
            }
        });
        return engineRoles;
    }

    private NimbusJwtDecoder decoder(String name, JwtProperties.IssuerProperties issuer) {
        boolean hasHmac = issuer.hmacSecret() != null && !issuer.hmacSecret().isBlank();
        boolean hasJwks = issuer.jwksUri() != null && !issuer.jwksUri().isBlank();
        if (hasHmac == hasJwks) {
            throw new IllegalArgumentException("Émetteur '" + name + "' : déclarer EXACTEMENT "
                    + "UNE source de clés (hmac-secret pour le test local, jwks-uri sinon).");
        }
        if (issuer.issuer() == null || issuer.issuer().isBlank()
                || issuer.audience() == null || issuer.audience().isBlank()) {
            throw new IllegalArgumentException(
                    "Émetteur '" + name + "' : issuer et audience sont obligatoires.");
        }
        if (hasHmac) {
            return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(
                            issuer.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                    .build();
        }
        return NimbusJwtDecoder.withJwkSetUri(issuer.jwksUri()).build();
    }
}
