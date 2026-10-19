package sn.gouv.mfp.workflow.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.service.RequestAttachmentService;
import sn.gouv.mfp.workflow.service.RequestAttachmentService.AttachmentView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.DraftView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.FormView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.SubmissionView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.TypeView;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;
import sn.gouv.mfp.workflow.web.dto.RequestDtos.FormDataRequest;

/**
 * API des demandes d'usagers (phase 3) : catalogue des types publiés, formulaire à servir,
 * brouillons et soumission créant le dossier du circuit, puis pièces jointes (FRM-05).
 */
@Tag(name = "Demandes des usagers",
        description = "Types de demande publiés, formulaires dynamiques, soumissions et pièces jointes.")
@RestController
@RequestMapping("/api")
public class RequestController {

    private final RequestSubmissionService submissions;
    private final RequestAttachmentService attachmentService;
    private final RequestActor requestActor;

    public RequestController(
            RequestSubmissionService submissions,
            RequestAttachmentService attachmentService,
            RequestActor requestActor) {
        this.submissions = submissions;
        this.attachmentService = attachmentService;
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
            summary = "Consulter un brouillon",
            description = "Saisie complète enregistrée, pour la reprendre dans le formulaire. "
                    + "Réservé à son auteur.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brouillon et sa saisie."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "403", description = "Brouillon d'un autre demandeur."),
        @ApiResponse(responseCode = "404", description = "Brouillon inconnu."),
        @ApiResponse(responseCode = "409", description = "La saisie a déjà été soumise.")
    })
    @GetMapping("/requests/drafts/{draftId}")
    public DraftView draft(
            @Parameter(description = "Identifiant du brouillon.") @PathVariable UUID draftId,
            Authentication authentication) {
        return submissions.draft(draftId, requestActor.resolve(authentication, null));
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

    @Operation(
            summary = "Déposer une pièce jointe",
            description = "FRM-05 : type MIME réel déduit du contenu (liste de types autorisés), "
                    + "taille maximale, empreinte SHA-256 et analyse antivirus AVANT mise à "
                    + "disposition. Un contenu refusé n'est jamais conservé. Le dépôt se fait sur "
                    + "un brouillon, par son auteur.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pièce jointe déposée et analysée."),
        @ApiResponse(responseCode = "400", description = "Champ inconnu, fichier vide ou contenu refusé par l'analyse antivirus."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Demande inconnue ou non visible."),
        @ApiResponse(responseCode = "413", description = "Pièce jointe trop volumineuse."),
        @ApiResponse(responseCode = "415", description = "Type de fichier non autorisé.")
    })
    @PostMapping(path = "/requests/submissions/{submissionId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView uploadAttachment(
            @Parameter(description = "Identifiant de la demande (brouillon).")
            @PathVariable UUID submissionId,
            @Parameter(description = "Champ du formulaire que la pièce renseigne.")
            @RequestParam("fieldPath") String fieldPath,
            @Parameter(description = "Contenu déposé.") @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        return attachmentService.store(
                submissionId,
                fieldPath,
                file.getOriginalFilename(),
                file.getContentType(),
                bytes(file),
                requestActor.resolve(authentication, null));
    }

    @Operation(
            summary = "Lister les pièces jointes d'une demande",
            description = "Brouillon : son auteur seul. Demande soumise : mêmes règles de "
                    + "visibilité que le dossier.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pièces jointes visibles."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Demande inconnue ou non visible.")
    })
    @GetMapping("/requests/submissions/{submissionId}/attachments")
    public List<AttachmentView> listAttachments(
            @Parameter(description = "Identifiant de la demande.") @PathVariable UUID submissionId,
            Authentication authentication) {
        return attachmentService.list(submissionId, Viewer.from(authentication));
    }

    @Operation(
            summary = "Lister les pièces jointes d'un dossier",
            description = "Fiche dossier : mêmes règles de visibilité que le dossier lui-même. "
                    + "Un dossier ouvert par le moteur sans demande rattachée n'a aucune pièce "
                    + "jointe (liste vide).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pièces jointes du dossier."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Dossier inconnu ou non visible.")
    })
    @GetMapping("/instances/{instanceId}/attachments")
    public List<AttachmentView> listInstanceAttachments(
            @Parameter(description = "Identifiant du dossier.") @PathVariable UUID instanceId,
            Authentication authentication) {
        return attachmentService.listForInstance(instanceId, Viewer.from(authentication));
    }

    @Operation(
            summary = "Télécharger une pièce jointe",
            description = "Le contenu n'est servi que si l'analyse antivirus est favorable, et "
                    + "selon les règles de visibilité du dossier ; l'empreinte SHA-256 est "
                    + "rappelée en en-tête.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contenu de la pièce jointe."),
        @ApiResponse(responseCode = "401", description = "Non authentifié."),
        @ApiResponse(responseCode = "404", description = "Pièce jointe inconnue ou dossier non visible."),
        @ApiResponse(responseCode = "409", description = "Analyse antivirus non favorable : contenu non servi.")
    })
    @GetMapping("/requests/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @Parameter(description = "Identifiant de la pièce jointe.") @PathVariable UUID attachmentId,
            Authentication authentication) {
        RequestAttachmentService.AttachmentDownload download =
                attachmentService.download(attachmentId, Viewer.from(authentication));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Content-SHA256", download.sha256())
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.content()));
    }

    /** Contenu du fichier déposé ; un contenu illisible est refusé comme une saisie invalide. */
    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new WorkflowValidationException(
                    "Le contenu de la pièce jointe n'a pas pu être lu.");
        }
    }
}
