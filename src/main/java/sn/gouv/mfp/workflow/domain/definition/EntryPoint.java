package sn.gouv.mfp.workflow.domain.definition;

import java.io.Serializable;

/**
 * Point d'entrée du circuit : rôle d'initiateur autorisé, étape où son dossier démarre,
 * et condition SpEL d'éligibilité optionnelle (MOT-04).
 * Persisté en JSONB sur la définition (donnée de configuration, jamais figée en SQL).
 */
public record EntryPoint(String role, String startStepKey, String condition) implements Serializable {

    public EntryPoint(String role, String startStepKey) {
        this(role, startStepKey, null);
    }
}
