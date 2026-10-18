package sn.gouv.mfp.workflow.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.service.WorkflowAuthorizationService;
import sn.gouv.mfp.workflow.service.WorkflowExecutionService;
import sn.gouv.mfp.workflow.service.WorkflowReadService;
import sn.gouv.mfp.workflow.service.WorkflowReadService.ActionOption;
import sn.gouv.mfp.workflow.service.WorkflowReadService.HistoryResponse;
import sn.gouv.mfp.workflow.service.WorkflowReadService.InstanceView;
import sn.gouv.mfp.workflow.service.WorkflowReadService.SearchCriteria;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;
import sn.gouv.mfp.workflow.web.dto.WorkflowDtos.StartWorkflowRequest;
import sn.gouv.mfp.workflow.web.dto.WorkflowDtos.TransitionRequest;
import sn.gouv.mfp.workflow.web.dto.WorkflowDtos.WorkflowInstanceResponse;

/**
 * API centrale du moteur de workflow : initiation des dossiers et exécution des transitions.
 */
@Tag(name = "Moteur de workflow", description = "Initiation et avancement des dossiers sur les circuits.")
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowExecutionService executionService;
    private final WorkflowReadService readService;
    private final WorkflowAuthorizationService authorizationService;
    private final RequestActor requestActor;

    public WorkflowController(
            WorkflowExecutionService executionService,
            WorkflowReadService readService,
            WorkflowAuthorizationService authorizationService,
            RequestActor requestActor) {
        this.executionService = executionService;
        this.readService = readService;
        this.authorizationService = authorizationService;
        this.requestActor = requestActor;
    }

    @Operation(
            summary = "Initier un dossier",
            description = "Crée un dossier ACTIVE sur la dernière version du circuit, à l'étape "
                    + "de départ demandée (défaut : première étape START). L'initiateur doit "
                    + "porter un rôle responsable de cette étape (owners du YAML) ; l'étape sans "
                    + "rôle responsable est refusée. Applique le plafond par initiateur configuré.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Dossier créé.",
                content = @Content(schema = @Schema(implementation = WorkflowInstanceResponse.class))),
        @ApiResponse(responseCode = "404", description = "Clé de circuit inconnue."),
        @ApiResponse(responseCode = "400", description = "Étape de départ invalide."),
        @ApiResponse(responseCode = "403", description = "Aucun rôle responsable de l'étape de départ."),
        @ApiResponse(responseCode = "409", description = "Plafond de dossiers actifs atteint.")
    })
    @PostMapping("/workflows/{definitionKey}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowInstanceResponse start(
            @Parameter(description = "Clé fonctionnelle du circuit.", example = "GIRAFE_ACTES")
            @PathVariable String definitionKey,
            @RequestBody StartWorkflowRequest request,
            Authentication authentication) {
        Map<String, Object> variables = request != null ? request.variables() : null;
        String initiatorId = request != null ? request.initiatorId() : null;
        String startStepKey = request != null ? request.startStepKey() : null;
        Viewer viewer = Viewer.from(authentication);
        authorizationService.authorizeStart(
                definitionKey, startStepKey, viewer, variables != null ? variables : Map.of());
        WorkflowInstance instance = executionService.startWorkflow(
                definitionKey,
                requestActor.resolve(authentication, initiatorId),
                variables,
                startStepKey,
                viewer.direction(),
                viewer.bureau(),
                viewer.roles());
        return WorkflowInstanceResponse.from(instance);
    }

    @Operation(
            summary = "Faire franchir une étape au dossier",
            description = "Exécute l'action demandée depuis l'étape courante : contrôle du rôle "
                    + "responsable de l'étape (owners du YAML), fusion des variables, données "
                    + "obligatoires, conditions SpEL, mise à jour et historisation. Un rôle "
                    + "administrateur n'ouvre jamais une action d'étape. Le contrôle du rôle est "
                    + "exécuté sous le verrou pessimiste (atomique avec la mutation).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transition appliquée.",
                content = @Content(schema = @Schema(implementation = WorkflowInstanceResponse.class))),
        @ApiResponse(responseCode = "400", description = "Aucune transition valide ou donnée obligatoire manquante."),
        @ApiResponse(responseCode = "403", description = "Aucun rôle responsable de l'étape courante."),
        @ApiResponse(responseCode = "404", description = "Dossier introuvable."),
        @ApiResponse(responseCode = "409", description = "Dossier non actif (déjà clôturé ou validé en concurrence).")
    })
    @PostMapping("/instances/{instanceId}/transition")
    public WorkflowInstanceResponse transition(
            @Parameter(description = "Identifiant du dossier.") @PathVariable UUID instanceId,
            @RequestBody TransitionRequest request,
            Authentication authentication) {
        Viewer viewer = Viewer.from(authentication);
        WorkflowInstance instance = executionService.executeTransition(
                instanceId,
                request.action(),
                requestActor.resolve(authentication, request.actorId()),
                request.comment(),
                request.variables(),
                viewer.roles(),
                viewer.direction(),
                viewer.bureau());
        return WorkflowInstanceResponse.from(instance);
    }

    @Operation(
            summary = "Consulter un dossier",
            description = "Dossier visible : initié ou imputé au demandeur, en boîte collective "
                    + "d'un de ses rôles, ou tout dossier pour un admin. Sinon 404.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dossier.",
                content = @Content(schema = @Schema(implementation = WorkflowInstanceResponse.class))),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Dossier introuvable ou hors périmètre.")
    })
    @GetMapping("/instances/{instanceId}")
    public WorkflowInstanceResponse getInstance(
            @Parameter(description = "Identifiant du dossier.") @PathVariable UUID instanceId,
            Authentication authentication) {
        return WorkflowInstanceResponse.from(
                readService.visibleOrThrow(instanceId, Viewer.from(authentication)));
    }

    @Operation(
            summary = "Consulter l'historique d'un dossier",
            description = "Traces chronologiques (action, acteur, motif, date UTC) d'un dossier lisible.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Historique chronologique."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Dossier introuvable ou hors périmètre.")
    })
    @GetMapping("/instances/{instanceId}/history")
    public List<HistoryResponse> getHistory(
            @Parameter(description = "Identifiant du dossier.") @PathVariable UUID instanceId,
            Authentication authentication) {
        return readService.history(instanceId, Viewer.from(authentication));
    }

    @Operation(
            summary = "Rechercher des dossiers",
            description = "Vues : MINE (initiés ou imputés au demandeur), INBOX (boîte collective "
                    + "des rôles, dossiers non imputés à autrui), ALL (admin). Taille de page "
                    + "rabattue à 100, tri stable (création décroissante). Le filtre initiateur "
                    + "d'un non-admin est sans effet. Limite v1 : le balayage porte sur les "
                    + "2000 dossiers les plus récents (pagination SQL avec le volume : 11.5).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page de dossiers."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Vue complète réservée aux admins.")
    })
    @GetMapping("/instances")
    public Page<WorkflowInstanceResponse> search(
            @Parameter(description = "Vue : MINE, INBOX ou ALL (admin).", example = "MINE")
            @RequestParam(defaultValue = "MINE") InstanceView view,
            @Parameter(description = "Clé fonctionnelle du circuit.") @RequestParam(required = false)
                    String definitionKey,
            @Parameter(description = "Statut.") @RequestParam(required = false) InstanceStatus status,
            @Parameter(description = "Étape courante.") @RequestParam(required = false)
                    String currentStepKey,
            @Parameter(description = "Initiateur (admin uniquement, ignoré sinon).")
            @RequestParam(required = false)
                    String initiatorId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        Page<WorkflowInstance> page = readService.search(
                Viewer.from(authentication),
                new SearchCriteria(view, definitionKey, status, currentStepKey, initiatorId),
                pageable);
        return page.map(WorkflowInstanceResponse::from);
    }

    @Operation(
            summary = "Lister les actions possibles",
            description = "Actions définies à l'étape courante avec leurs cibles et les données "
                    + "requises. Vide si le dossier est terminé.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Actions possibles (structurelles)."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Dossier introuvable ou hors périmètre.")
    })
    @GetMapping("/instances/{instanceId}/actions")
    public List<ActionOption> possibleActions(
            @Parameter(description = "Identifiant du dossier.") @PathVariable UUID instanceId,
            Authentication authentication) {
        return readService.possibleActions(instanceId, Viewer.from(authentication));
    }
}
