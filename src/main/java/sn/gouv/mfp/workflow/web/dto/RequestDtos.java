package sn.gouv.mfp.workflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Contrats d'entrée de l'API des demandes d'usagers (REQ-01, REQ-02, REQ-03). */
public final class RequestDtos {

    private RequestDtos() {
    }

    /**
     * Demande d'usager : type de demande visé et champs saisis. Le demandeur n'est jamais
     * transmis : l'identité certifiée du jeton est celle qui soumet (SEC-03).
     */
    @Schema(description = "Saisie d'une demande d'usager.")
    public record FormDataRequest(
            @Schema(description = "Clé métier du type de demande.", example = "CERTIFICAT_TRAVAIL")
            String typeKey,
            @Schema(description = "Champs saisis, validés contre le JSON Schema publié du type.",
                    example = "{\"nom\": \"Awa Diop\", \"motif\": \"Promotion\"}")
            Map<String, Object> formData) {
    }
}
