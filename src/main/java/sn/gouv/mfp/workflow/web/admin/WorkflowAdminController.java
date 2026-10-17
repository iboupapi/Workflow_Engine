package sn.gouv.mfp.workflow.web.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.WorkflowDefinitionLoaderService;
import sn.gouv.mfp.workflow.service.dto.DefinitionImport;

/**
 * Administration du moteur : import (ou rechargement) à chaud d'un circuit
 * depuis sa configuration YAML, sans redémarrage. Chaque import crée une nouvelle version.
 */
@Tag(name = "Administration", description = "Import à chaud des définitions de circuits (YAML).")
@RestController
@RequestMapping("/api/admin/workflows")
public class WorkflowAdminController {

    private final WorkflowDefinitionLoaderService loaderService;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;

    public WorkflowAdminController(
            WorkflowDefinitionLoaderService loaderService,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions) {
        this.loaderService = loaderService;
        this.steps = steps;
        this.transitions = transitions;
    }

    @Operation(
            summary = "Importer un circuit (YAML brut)",
            description = "Valide la cohérence du graphe puis persiste une nouvelle version "
                    + "de la définition. Corps brut avec Content-Type application/x-yaml ou texte. "
                    + "Variante fichier disponible en multipart/form-data (champ 'file').",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Contenu du fichier de configuration YAML.",
                    content = @Content(
                            mediaType = "application/x-yaml",
                            schema = @Schema(type = "string", format = "yaml"))))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Définition importée (nouvelle version).",
                content = @Content(schema = @Schema(implementation = WorkflowDefinitionResponse.class))),
        @ApiResponse(responseCode = "200", description = "Contenu identique : version existante réutilisée.",
                content = @Content(schema = @Schema(implementation = WorkflowDefinitionResponse.class))),
        @ApiResponse(responseCode = "400", description = "YAML illisible ou graphe incohérent."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis (JWT dont le sujet est déclaré dans auth.admin.subjects).")
    })
    @PostMapping(
            path = "/import",
            consumes = {
                "application/x-yaml",
                "application/yaml",
                "text/yaml",
                "text/plain"
            })
    public ResponseEntity<WorkflowDefinitionResponse> importYaml(
            @RequestBody String yamlContent) {
        return toResponse(loaderService.loadAndSaveYamlDefinition(yamlContent));
    }

    @Operation(
            summary = "Importer un circuit (fichier multipart)",
            description = "Équivalent fichier de l'import YAML brut (champ de formulaire 'file').")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Définition importée (nouvelle version).",
                content = @Content(schema = @Schema(implementation = WorkflowDefinitionResponse.class))),
        @ApiResponse(responseCode = "200", description = "Contenu identique : version existante réutilisée.",
                content = @Content(schema = @Schema(implementation = WorkflowDefinitionResponse.class))),
        @ApiResponse(responseCode = "400", description = "YAML illisible ou graphe incohérent."),
        @ApiResponse(responseCode = "403", description = "Administrateur nommé requis (JWT dont le sujet est déclaré dans auth.admin.subjects).")
    })
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkflowDefinitionResponse> importYamlFile(
            @Parameter(description = "Fichier de configuration YAML du circuit.") @RequestParam("file")
                    MultipartFile file)
            throws IOException {
        return toResponse(loaderService.loadAndSaveYamlDefinition(
                new String(file.getBytes(), StandardCharsets.UTF_8), file.getOriginalFilename()));
    }

    private ResponseEntity<WorkflowDefinitionResponse> toResponse(DefinitionImport result) {
        WorkflowDefinition definition = result.definition();
        WorkflowDefinitionResponse body = new WorkflowDefinitionResponse(
                definition.getKey(),
                definition.getName(),
                definition.getVersion(),
                steps.findByDefinitionId(definition.getId()).size(),
                countTransitions(definition));
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.ok(body);
    }

    private int countTransitions(WorkflowDefinition definition) {
        return steps.findByDefinitionId(definition.getId()).stream()
                .mapToInt(step -> transitions.findBySourceStepId(step.getId()).size())
                .sum();
    }

    /** Synthèse de la définition déployée (version créée ou réutilisée). */
    @Schema(description = "Synthèse de la définition de circuit déployée.")
    public record WorkflowDefinitionResponse(
            @Schema(description = "Clé fonctionnelle du circuit.", example = "GIRAFE_ACTES")
            String key,
            @Schema(description = "Nom lisible du circuit.") String name,
            @Schema(description = "Version créée par cet import.", example = "4") Integer version,
            @Schema(description = "Nombre d'étapes persistées.", example = "13") int stepCount,
            @Schema(description = "Nombre de transitions persistées.", example = "24")
            int transitionCount) {
    }
}
