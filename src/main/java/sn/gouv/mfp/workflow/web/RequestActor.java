package sn.gouv.mfp.workflow.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Identité d'exécution (cahier de recette 2.5) : avec un JWT, l'acteur est TOUJOURS
 * le sujet du jeton — la valeur du corps de requête est ignorée (elle ne peut ni
 * usurper ni compléter l'identité). Sans JWT (jeton administrateur intérimaire),
 * l'acteur du corps est conservé.
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
