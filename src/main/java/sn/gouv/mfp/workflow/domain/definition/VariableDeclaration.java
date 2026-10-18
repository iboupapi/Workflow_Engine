package sn.gouv.mfp.workflow.domain.definition;

import java.io.Serializable;

/**
 * Variable de dossier déclarée par un circuit (MOT-10) : clé, type contrôlé
 * ({@code string}, {@code number}, {@code boolean}, {@code date}) et caractère obligatoire
 * dès la création du dossier.
 * Persisté en JSONB sur la définition (donnée de configuration, jamais figée en SQL).
 */
public record VariableDeclaration(String key, String type, boolean required) implements Serializable {
}
