package sn.gouv.mfp.workflow.web.admin;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeDetailView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.DeployedWorkflowView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.FormSchemaView;
import sn.gouv.mfp.workflow.web.dto.AdminDtos.CreateFormSchemaRequest;
import sn.gouv.mfp.workflow.web.dto.AdminDtos.CreateRequestTypeRequest;
import sn.gouv.mfp.workflow.web.dto.AdminDtos.PublishRequestTypeVersionRequest;

/**
 * Administration des E-Services (REQ-01, REQ-02, FRM-01) : créer un type de demande,
 * enregistrer son formulaire et publier une version immuable qui le rend proposable. Le
 * contrôle d'accès est celui de {@code /api/admin/**} : administrateur <strong>nommé</strong>
 * (sujet du jeton déclaré dans {@code auth.admin.subjects}, SEC-03).
 */
@Tag(name = "Administration — E-Services",
        description = "Référentiel des types de demande, formulaires et publications.")
@RestController
@RequestMapping("/api/admin")
public class AdminRequestTypeController {

    private final AdminRequestTypeService service;

    public AdminRequestTypeController(AdminRequestTypeService service) {
        this.service = service;
    }

    @Operation(
            summary = "Lister tous les types de demande",
            description = "Référentiel COMPLET (DRAFT, PUBLISHED, RETIRED), dernière version "
                    + "comprise : c'est le tableau de bord de l'administration, là où le "
                    + "catalogue usager ne montre que les types publiés.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Référentiel complet."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis.")
    })
    @GetMapping("/request-types")
    public List<AdminRequestTypeView> types() {
        return service.listAll();
    }

    @Operation(
            summary = "Détail d'un type de demande",
            description = "Version en vigueur, schéma de formulaire et liaison de champs : de "
                    + "quoi rouvrir l'éditeur sur un E-Service existant.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Type et sa version en vigueur."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis."),
        @ApiResponse(responseCode = "404", description = "Type inconnu.")
    })
    @GetMapping("/request-types/{requestTypeId}")
    public AdminRequestTypeDetailView type(
            @Parameter(description = "Identifiant du type de demande.") @PathVariable
                    UUID requestTypeId) {
        return service.detail(requestTypeId);
    }

    @Operation(
            summary = "Créer un type de demande",
            description = "Crée le référentiel en brouillon : le type n'est proposé à l'usager "
                    + "qu'une fois une version publiée. Aucune validation en écriture SQL.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Type créé en brouillon.",
                content = @Content(schema = @Schema(implementation = AdminRequestTypeView.class))),
        @ApiResponse(responseCode = "400", description = "Clé ou libellé invalide."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis."),
        @ApiResponse(responseCode = "409", description = "Clé déjà utilisée par un autre type.")
    })
    @PostMapping("/request-types")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRequestTypeView createType(@RequestBody CreateRequestTypeRequest request) {
        return service.create(request.typeKey(), request.label(), request.category());
    }

    @Operation(
            summary = "Enregistrer un schéma de formulaire",
            description = "Contrôle le JSON Schema 2020-12 (compilable, objet racine portant des "
                    + "champs) puis le conserve. L'identifiant renvoyé est celui à rattacher à la "
                    + "publication.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Schéma enregistré.",
                content = @Content(schema = @Schema(implementation = FormSchemaView.class))),
        @ApiResponse(responseCode = "400", description = "Schéma vide, invalide ou inexploitable."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis.")
    })
    @PostMapping("/form-schemas")
    @ResponseStatus(HttpStatus.CREATED)
    public FormSchemaView createFormSchema(@RequestBody CreateFormSchemaRequest request) {
        return service.createFormSchema(request.schemaJson(), request.uiSchema());
    }

    @Operation(
            summary = "Publier une version de type de demande",
            description = "Assemble le type, le formulaire et le circuit, contrôle la liaison des "
                    + "champs (REQ-02) puis publie une version immuable. Le type devient "
                    + "immédiatement proposable dans le catalogue ; republier crée la version "
                    + "suivante.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Version publiée.",
                content = @Content(schema = @Schema(implementation = AdminRequestTypeView.class))),
        @ApiResponse(responseCode = "400", description = "Liaison de champs incohérente ou clé de circuit absente."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis."),
        @ApiResponse(responseCode = "404", description = "Type, schéma de formulaire ou circuit inconnu.")
    })
    @PostMapping("/request-type-versions/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRequestTypeView publish(
            @RequestBody PublishRequestTypeVersionRequest request) {
        return service.publish(
                request.requestTypeId(),
                request.formSchemaId(),
                request.workflowDefinitionKey(),
                request.fieldMapping());
    }

    @Operation(
            summary = "Lister les circuits déployés",
            description = "Dernière version de chaque clé de circuit : ce qui peut être rattaché "
                    + "à un type de demande.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Circuits disponibles."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis.")
    })
    @GetMapping("/workflows")
    public List<DeployedWorkflowView> workflows() {
        return service.deployedWorkflows();
    }
}
