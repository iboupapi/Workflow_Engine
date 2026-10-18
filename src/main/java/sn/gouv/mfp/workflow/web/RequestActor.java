package sn.gouv.mfp.workflow.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Identité certifiée de l'acteur d'exécution (cahier de recette 2.5).
 * <p>
 * Avec un JWT, l'acteur est TOUJOURS l'identité portée par le jeton : le sujet (claim
 * {@code sub}) préfixé par le nom de l'émetteur ({@code nomEmetteur:sub}, SEC-03) — la
 * valeur {@code actorId}/{@code initiatorId} du corps de requête est ignorée et ne
 * peut donc jamais usurper ni compléter l'identité. Le repli sur la valeur du corps
 * n'existe que pour les tests : en production, une requête sans JWT est rejetée
 * (401) par la chaîne de sécurité avant d'atteindre le contrôleur.
 */
@Component
public class RequestActor {

    /**
     * @param authentication authentification courante (peut être nulle en test)
     * @param bodyActorId    acteur déclaré dans le corps de la requête
     */
    public String resolve(Authentication authentication, String bodyActorId) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getName();
        }
        return bodyActorId;
    }
}
