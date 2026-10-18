package sn.gouv.mfp.workflow.service.dto;

import java.util.List;

/**
 * Étape 5 — miroir Java de la configuration YAML d'un circuit.
 * Les champs inconnus sont REFUSÉS à l'import (une faute de frappe ne doit pas
 * désactiver une règle en silence) ; le champ fautif est nommé dans l'erreur 400.
 * Voir {@code YamlConfig} (FAIL_ON_UNKNOWN_PROPERTIES).
 */
public record WorkflowConfigDTO(
        DefinitionConfigDTO definition,
        SettingsConfigDTO settings,
        List<EntryPointConfigDTO> entryPoints,
        /** Variables de dossier déclarées (bloc obligatoire, recette 1.5) : clés autorisées dans
         *  les conditions SpEL, type contrôlé et caractère obligatoire dès la création (MOT-10). */
        List<VariableConfigDTO> variables,
        List<StepConfigDTO> steps,
        List<TransitionConfigDTO> transitions) {

    public record DefinitionConfigDTO(String key, String name, Integer version) {
    }

    public record SettingsConfigDTO(
            Integer maxActiveInstancesPerInitiator,
            Integer maxActiveInstancesPerType,
            List<ActiveInstanceLimitConfigDTO> activeInstanceLimits,
            String resumeStrategyDefault) {
    }

    /**
     * Plafond de dossiers actifs scopé par RÔLE initiateur et par type d'acte (MOT-05).
     * Il ne s'applique qu'aux initiateurs portant {@code role} ; les autres rôles ne sont
     * pas plafonnés par cette règle (ex. seul l'agent de bureau est plafonné, pas les chefs).
     */
    public record ActiveInstanceLimitConfigDTO(
            String role,
            Integer maxPerType,
            Integer maxPerInitiator) {
    }

    /**
     * Déclaration d'une variable de dossier (MOT-10) : clé, type contrôlé
     * ({@code string}, {@code number}, {@code boolean}, {@code date}) et caractère
     * obligatoire dès la création. {@code required} absent vaut {@code false}.
     */
    public record VariableConfigDTO(String key, String type, Boolean required) {
    }

    public record EntryPointConfigDTO(
            String role,
            @com.fasterxml.jackson.annotation.JsonAlias({"step"})
            String startStepKey,
            String condition) {
        public EntryPointConfigDTO(String role, String startStepKey) {
            this(role, startStepKey, null);
        }
    }

    public record StepConfigDTO(
            String key,
            String name,
            String type,
            List<String> requiredDataOnValidate,
            List<String> owners) {
    }

    public record TransitionConfigDTO(
            @com.fasterxml.jackson.annotation.JsonAlias({"from"})
            String source,
            String action,
            @com.fasterxml.jackson.annotation.JsonAlias({"to"})
            String target,
            String condition,
            /** Marqueur de traitement (setsHandler: true) : son franchissement mémorise l'acteur
             *  et l'étape d'origine, cible de RESPONSABLE_TRAITEMENT. */
            Boolean setsHandler) {
        public TransitionConfigDTO(String source, String action, String target, String condition) {
            this(source, action, target, condition, null);
        }
    }
}
