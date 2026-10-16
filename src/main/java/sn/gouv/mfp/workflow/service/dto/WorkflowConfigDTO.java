package sn.gouv.mfp.workflow.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Étape 5 — miroir Java de la configuration YAML d'un circuit.
 * Les champs inconnus sont ignorés pour permettre l'évolution du format sans casser l'import.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowConfigDTO(
        DefinitionConfigDTO definition,
        SettingsConfigDTO settings,
        List<EntryPointConfigDTO> entryPoints,
        /** Clés de dossier autorisées dans les conditions SpEL (bloc obligatoire, recette 1.5). */
        List<String> variables,
        List<StepConfigDTO> steps,
        List<TransitionConfigDTO> transitions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DefinitionConfigDTO(String key, String name, Integer version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettingsConfigDTO(
            Integer maxActiveInstancesPerInitiator, String resumeStrategyDefault) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntryPointConfigDTO(String role, String startStepKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepConfigDTO(
            String key,
            String name,
            String type,
            List<String> requiredDataOnValidate,
            List<String> owners,
            /** Étape de traitement de référence (cible RESPONSABLE_TRAITEMENT) : au plus une par circuit. */
            Boolean processingStep) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitionConfigDTO(
            String source, String action, String target, String condition) {
    }
}
