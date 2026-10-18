package sn.gouv.mfp.workflow.web.dev;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.web.security.JwtProperties;

/**
 * Fabrique de jetons de DÉVELOPPEMENT (SEC-08) : émet, à la demande, un JWT signé HMAC
 * valide pour chaque rôle moteur de l'émetteur local, afin que la console puisse agir
 * sans attendre l'annuaire réel.
 * <p>
 * <strong>Jamais de secret en dur</strong> : la clé est celle de l'émetteur HMAC configuré
 * ({@code auth.jwt.issuers.<nom>.hmac-secret}, alimentée par {@code TEST_ISSUER_SECRET}).
 * Les rôles du jeton sont ceux que la correspondance de l'émetteur accepte pour le rôle
 * moteur demandé — le jeton produit est donc exactement celui qu'un usager réel présenterait.
 * Ce service n'est monté qu'en profil {@code dev}.
 */
@Service
@Profile("dev")
public class DevTokenService {

    /** Durée de vie volontairement courte : jeton de mise au point, pas de session. */
    static final Duration LIFETIME = Duration.ofHours(8);

    private final JwtProperties properties;

    public DevTokenService(JwtProperties properties) {
        this.properties = properties;
    }

    /** Un jeton par rôle moteur déclaré dans la correspondance de l'émetteur dev. */
    public List<DevToken> allTokens(String direction, String bureau) {
        return issuer().roleMapping().keySet().stream()
                .sorted()
                .map(role -> token(role, direction, bureau))
                .toList();
    }

    /** Jeton pour un rôle moteur ; 400 si le rôle n'est pas déclaré (refus explicite). */
    public DevToken token(String role, String direction, String bureau) {
        JwtProperties.IssuerProperties issuer = issuer();
        List<String> tokenRoles = issuer.roleMapping().get(role);
        if (tokenRoles == null) {
            throw new WorkflowValidationException("Rôle '" + role + "' inconnu de l'émetteur "
                    + "de développement. Rôles connus : "
                    + String.join(", ", issuer.roleMapping().keySet().stream().sorted().toList()));
        }
        String subject = "dev-" + role.toLowerCase(Locale.ROOT);
        Instant expiresAt = Instant.now().plus(LIFETIME);
        String token = sign(issuer, subject, tokenRoles, direction, bureau, expiresAt);
        return new DevToken(role, subject, token, expiresAt);
    }

    /**
     * Émetteur de développement : le premier émetteur HMAC déclaré (clé de configuration
     * croissante, pour un choix déterministe). 409 si aucun n'est configuré : le profil
     * dev a été démarré sans émetteur de test.
     */
    private JwtProperties.IssuerProperties issuer() {
        return properties.issuers() == null
                ? noIssuer()
                : properties.issuers().entrySet().stream()
                        .filter(entry -> entry.getValue().hmacSecret() != null
                                && !entry.getValue().hmacSecret().isBlank())
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElseGet(DevTokenService::noIssuer);
    }

    private static JwtProperties.IssuerProperties noIssuer() {
        throw new WorkflowConflictException("Aucun émetteur HMAC de développement configuré "
                + "(auth.jwt.issuers.<nom>.hmac-secret alimenté par TEST_ISSUER_SECRET).");
    }

    private String sign(
            JwtProperties.IssuerProperties issuer,
            String subject,
            List<String> roles,
            String direction,
            String bureau,
            Instant expiresAt) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer.issuer())
                    .audience(issuer.audience())
                    .subject(subject)
                    .claim(
                            issuer.rolesClaim() != null && !issuer.rolesClaim().isBlank()
                                    ? issuer.rolesClaim()
                                    : "roles",
                            roles)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt));
            if (direction != null && !direction.isBlank()) {
                claims.claim("direction", direction);
            }
            if (bureau != null && !bureau.isBlank()) {
                claims.claim("bureau", bureau);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(issuer.hmacSecret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Signature du jeton de développement impossible.", e);
        }
    }

    /** Jeton émis pour un rôle moteur (structure exposée à la console). */
    public record DevToken(String role, String subject, String token, Instant expiresAt) {
    }
}
