package sn.gouv.mfp.workflow.web.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Administrateurs <strong>nommés</strong> du moteur (remplace le jeton fixe intérimaire).
 * <p>
 * Un administrateur est une identité réelle et <strong>préfixée par son émetteur</strong> :
 * {@code nomEmetteur:sub} (SEC-03), où {@code nomEmetteur} est la clé de configuration de
 * l'émetteur (ex. {@code agents:agent-001}). C'est cette identité complète qui doit figurer
 * dans cette liste pour obtenir le rôle {@code ADMIN} — comparer le seul {@code sub}
 * permettrait à un émetteur de se faire passer pour un administrateur d'un autre émetteur.
 * Aucune valeur par défaut : sans identité nommée, personne n'est administrateur (refus par défaut).
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
