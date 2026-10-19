package sn.gouv.mfp.workflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

/**
 * Contrats d'entrée de l'administration des E-Services : création d'un type de demande,
 * enregistrement d'un schéma de formulaire, puis publication d'une version qui assemble
 * type, formulaire et circuit (REQ-01, REQ-02, FRM-01).
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** Création d'un type de demande : identité fonctionnelle seulement, en brouillon. */
    @Schema(description = "Nouveau type de demande proposé aux usagers.")
    public record CreateRequestTypeRequest(
            @Schema(description = "Clé métier, unique et stable (majuscules, chiffres, "
                    + "soulignés ; utilisée dans les URL).", example = "CONGE_MATERNITE")
            String typeKey,
            @Schema(description = "Libellé présenté à l'usager.", example = "Demande de congé maternité")
            String label,
            @Schema(description = "Catégorie de rattachement (facultative).",
                    example = "Congés & absences")
            String category) {
    }

    /**
     * Enregistrement d'un schéma de formulaire : JSON Schema 2020-12 (validation serveur) et
     * UI Schema (présentation). Le schéma est contrôlé avant d'être conservé.
     */
    @Schema(description = "Schéma de formulaire d'un type de demande.")
    public record CreateFormSchemaRequest(
            @Schema(description = "JSON Schema 2020-12 du formulaire (objet racine avec des champs).",
                    example = "{\"type\": \"object\", \"properties\": {\"dateDebut\": "
                            + "{\"type\": \"string\", \"format\": \"date\"}}, "
                            + "\"required\": [\"dateDebut\"]}")
            Map<String, Object> schemaJson,
            @Schema(description = "UI Schema : libellés, aides et ordre des champs (facultatif).")
            Map<String, Object> uiSchema) {
    }

    /**
     * Publication : assemble une version immuable. Le type passe en PUBLISHED et devient
     * proposé aux usagers ; une republication crée la version suivante (jamais de
     * modification en place).
     */
    @Schema(description = "Publication d'une version de type de demande.")
    public record PublishRequestTypeVersionRequest(
            @Schema(description = "Type de demande visé.") UUID requestTypeId,
            @Schema(description = "Schéma de formulaire de cette version.") UUID formSchemaId,
            @Schema(description = "Clé du circuit qui traitera les demandes.",
                    example = "E_SERVICE_DEMANDE")
            String workflowDefinitionKey,
            @Schema(description = "Liaison des champs du formulaire vers les variables de décision "
                    + "du circuit (REQ-02), facultative.",
                    example = "{\"typeActe\": \"natureActe\"}")
            Map<String, Object> fieldMapping) {
    }
}
