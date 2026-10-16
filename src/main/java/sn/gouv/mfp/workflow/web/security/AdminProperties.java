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

    /**
     * Rôle réservé d'administration. Il est accordé <strong>uniquement</strong> aux sujets
     * nommés ({@link #subjects()}) ; il n'est <strong>jamais</strong> dérivé des rôles du
     * jeton et ne peut pas être déclaré dans les {@code owners} d'une étape.
     */
    public static final String ADMIN_ROLE = "ADMIN";

    /** Sujets administrateurs configurés (jamais nuls, éventuellement vides). */
    public List<String> subjectsOrEmpty() {
        return subjects != null ? subjects : List.of();
    }
}
