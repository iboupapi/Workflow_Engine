package sn.gouv.mfp.workflow.web.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Administrateurs <strong>nommés</strong> du moteur (remplace le jeton fixe intérimaire).
 * <p>
 * Un administrateur est une identité réelle : son sujet ({@code sub} du JWT) doit figurer
 * dans cette liste pour obtenir le rôle {@code ADMIN}. Aucune valeur par défaut : sans
 * sujet nommé, personne n'est administrateur (refus par défaut).
 * <p>
 * Un administrateur administre et supervise : il n'est <strong>jamais</strong> validateur
 * d'étape (un rôle {@code ADMIN} déclaré dans {@code owners} est refusé à l'import).
 */
@ConfigurationProperties(prefix = "auth.admin")
public record AdminProperties(List<String> subjects) {

    /** Sujets administrateurs configurés (jamais nuls, éventuellement vides). */
    public List<String> subjectsOrEmpty() {
        return subjects != null ? subjects : List.of();
    }
}
