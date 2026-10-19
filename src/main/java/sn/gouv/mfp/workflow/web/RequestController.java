package sn.gouv.mfp.workflow.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.gouv.mfp.workflow.service.RequestSubmissionService;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.FormView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.SubmissionView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.TypeView;
import sn.gouv.mfp.workflow.web.dto.RequestDtos.FormDataRequest;

/**
 * API des demandes d'usagers (phase 3) : catalogue des types publiés, formulaire à servir,
 * brouillons et soumission créant le dossier du circuit.
 */
@Tag(name = "Demandes des usagers",
        description = "Types de demande publiés, formulaires dynamiques et soumissions.")
@RestController
@RequestMapping("/api")
public class RequestController {

    private final RequestSubmissionService submissions;
    private final RequestActor requestActor;

    public RequestController(RequestSubmissionService submissions, RequestActor requestActor) {
        this.submissions = submissions;
        this.requestActor = requestActor;
    }

    @Operation(
            summary = "Lister les types de demande proposés",
            description = "Référentiel publié, trié par libellé : ce que l'usager peut demander.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Catalogue publié."),
        @ApiResponse(responseCode = "401", description = "Non authentifié.")
    })
    @GetMapping("/request-types")
    public List<TypeView> types() {
        return submissions.publishedTypes();
    }

    @Operation(
            summary = "Formulaire d'un type de demande",
            description = "JSON Schema 2020-12 et UI Schema de la version publiée en vigueur. "
                    + "Ces données alimentent la présentation du formulaire ; la validation "
                    + "serveur reste celle de la soumission (FRM-03).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Formulaire de la version en vigueur."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Type inconnu, non publié ou sans version publiée.")
    })
    @GetMapping("/request-types/{typeKey}/form")
    public FormView form(
            @Parameter(description = "Clé métier du type de demande.", example = "CERTIFICAT_TRAVAIL")
            @PathVariable String typeKey) {
        return submissions.activeForm(typeKey);
    }

    @Operation(
            summary = "Soumettre une demande",
            description = "Valide la saisie contre le formulaire publié (400 avec le détail des "
                    + "écarts, sans rien enregistrer), en dérive les variables de décision selon "
                    + "la liaison déclarée, puis crée le dossier du circuit. Le demandeur est "
                    + "l'identité certifiée du jeton.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Demande soumise et dossier créé.",
                content = @Content(schema = @Schema(implementation = SubmissionView.class))),
        @ApiResponse(responseCode = "400", description = "Saisie non conforme au formulaire publié."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Type inconnu, non publié ou sans version publiée."),
        @ApiResponse(responseCode = "409", description = "Plafond de dossiers actifs atteint.")
    })
    @PostMapping("/requests/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionView submit(
            @RequestBody FormDataRequest request, Authentication authentication) {
        return submissions.submit(
                request.typeKey(),
                request.formData(),
                requestActor.resolve(authentication, null));
    }

    @Operation(
            summary = "Enregistrer un brouillon",
            description = "Conserve une saisie incomplète, sans dossier : elle est reprenable "
                    + "et modifiable par son auteur. Aucune validation de formulaire n'est faite "
                    + "à ce stade.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Brouillon enregistré."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Type inconnu, non publié ou sans version publiée.")
    })
    @PostMapping("/requests/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionView saveDraft(
            @RequestBody FormDataRequest request, Authentication authentication) {
        return submissions.saveDraft(
                request.typeKey(),
                request.formData(),
                requestActor.resolve(authentication, null));
    }

    @Operation(
            summary = "Lister ses brouillons",
            description = "Brouillons du demandeur authentifié, du plus récent au plus ancien.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brouillons du demandeur."),
        @ApiResponse(responseCode = "401", description = "Non authentifié.")
    })
    @GetMapping("/requests/drafts")
    public List<SubmissionView> drafts(Authentication authentication) {
        return submissions.drafts(requestActor.resolve(authentication, null));
    }

    @Operation(
            summary = "Soumettre un brouillon",
            description = "Valide la saisie enregistrée puis crée le dossier ; le brouillon "
                    + "devient la demande définitive. Réservé à son auteur, une seule fois.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brouillon soumis, dossier créé."),
        @ApiResponse(responseCode = "400", description = "Saisie non conforme au formulaire publié."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Brouillon d'un autre demandeur."),
        @ApiResponse(responseCode = "404", description = "Brouillon inconnu.")
    })
    @PostMapping("/requests/drafts/{draftId}/submit")
    public SubmissionView submitDraft(
            @Parameter(description = "Identifiant du brouillon.") @PathVariable UUID draftId,
            Authentication authentication) {
        return submissions.submitDraft(draftId, requestActor.resolve(authentication, null));
    }
}
