package sn.gouv.mfp.workflow.web.security;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Émetteurs JWT configurés (cahier de recette, section 2).
 * <p>
 * Chaque émetteur déclare EXACTEMENT UNE source de clés : {@code hmac-secret} partagé
 * (émetteur de test local uniquement) OU {@code jwks-uri} distante (annuaire, portail).
 * Sans émetteur configuré, aucun jeton n'est accepté (refus par défaut).
 * Émetteurs réels, rôles et arbitrage moteur/applications : à confirmer avec le
 * ministère avant le pilote (§14 du cahier de recette).
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(Map<String, IssuerProperties> issuers) {

    /**
     * @param issuer       valeur exacte de la revendication {@code iss}
     * @param audience     valeur exigée dans {@code aud}
     * @param hmacSecret   clé partagée (émetteur de test local uniquement)
     * @param jwksUri      jeu de clés distant (annuaire, portail)
     * @param rolesClaim   revendication liste portant les rôles du jeton
     * @param roleMapping  correspondance <strong>rôle moteur → rôles du jeton</strong> :
     *                     un rôle moteur (celui employé dans {@code owners} des étapes)
     *                     est accordé dès que le jeton porte l'un des rôles listés.
     *                     <strong>Vide = aucun rôle accordé</strong> (jamais l'identité du
     *                     jeton). Les rôles du jeton non reconnus ne donnent aucun droit,
     *                     et le rôle réservé {@code ADMIN} n'est jamais dérivé du jeton.
     */
    public record IssuerProperties(
            String issuer,
            String audience,
            String hmacSecret,
            String jwksUri,
            String rolesClaim,
            Map<String, List<String>> roleMapping) {
    }
}
