package sn.gouv.mfp.workflow.domain.definition;

import java.io.Serializable;

/**
 * Point d'entrée du circuit : rôle d'initiateur autorisé et étape START où son dossier démarre.
 * Persisté en JSONB sur la définition (donnée de configuration, jamais figée en SQL).
 */
public record EntryPoint(String role, String startStepKey) implements Serializable {
}
