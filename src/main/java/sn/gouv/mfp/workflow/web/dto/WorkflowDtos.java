package sn.gouv.mfp.workflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;

/** Contrats d'entrée/sortie de l'API centrale du moteur. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /** Corps de POST /api/workflows/{definitionKey}/start. */
    @Schema(description = "Demande d'initiation d'un dossier sur un circuit.")
    public record StartWorkflowRequest(
            @Schema(description = "Identifiant de l'agent initiateur (ex. matricule).", example = "SN-042")
            String initiatorId,
            @Schema(description = "Données initiales du dossier, stockées en JSONB.",
                    example = "{\"circuit\": \"COURT\", \"natureActe\": \"ARRETE\"}")
            Map<String, Object> variables,
            @Schema(description = "Étape de départ imposée (point d'entrée du YAML). "
                    + "Null : première étape START trouvée.", example = "AGENT_BUREAU", nullable = true)
            String startStepKey) {
    }

    /** Corps de POST /api/instances/{instanceId}/transition. */
    @Schema(description = "Demande d'exécution d'une transition sur un dossier.")
    public record TransitionRequest(
            @Schema(description = "Déclencheur configuré sur la transition.", example = "VALIDER")
            String action,
            @Schema(description = "Identifiant de l'agent exécutant l'action.", example = "SN-042")
            String actorId,
            @Schema(description = "Motif, obligatoire en cas de rejet.", nullable = true)
            String comment,
            @Schema(description = "Variables fusionnées dans le dossier avant évaluation.",
                    nullable = true)
            Map<String, Object> variables) {
    }

    /** Dossier exposé en réponse (jamais d'entité JPA sérialisée directement). */
    @Schema(description = "Dossier en cours de circulation.")
    public record WorkflowInstanceResponse(
            @Schema(description = "Identifiant du dossier.") UUID id,
            @Schema(description = "Clé fonctionnelle du circuit suivi.", example = "GIRAFE_ACTES")
            String definitionKey,
            @Schema(description = "Version du circuit suivie par ce dossier.", example = "3")
            Integer definitionVersion,
            @Schema(description = "Étape courante du dossier.", example = "S_VISA")
            String currentStepKey,
            @Schema(description = "Statut du dossier.") InstanceStatus status,
            @Schema(description = "Identifiant de l'agent initiateur.") String initiatorId,
            @Schema(description = "Personne à qui le dossier est imputé (null si en boîte collective).",
                    nullable = true)
            String assigneeId,
            @Schema(description = "Horodatage UTC de création.") java.time.Instant createdAt,
            @Schema(description = "Données métier du dossier (JSONB).") Map<String, Object> variables) {

        public static WorkflowInstanceResponse from(WorkflowInstance instance) {
            return new WorkflowInstanceResponse(
                    instance.getId(),
                    instance.getDefinition().getKey(),
                    instance.getDefinition().getVersion(),
                    instance.getCurrentStepKey(),
                    instance.getStatus(),
                    instance.getInitiatorId(),
                    instance.getAssigneeId(),
                    instance.getCreatedAt(),
                    instance.getVariables());
        }
    }
}
