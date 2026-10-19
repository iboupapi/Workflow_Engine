package sn.gouv.mfp.workflow.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.domain.request.AttachmentScanStatus;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowPayloadTooLargeException;
import sn.gouv.mfp.workflow.exception.WorkflowUnsupportedMediaTypeException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.service.RequestAttachmentService;
import sn.gouv.mfp.workflow.service.RequestAttachmentService.AttachmentView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.FormView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.SubmissionView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.TypeView;
import sn.gouv.mfp.workflow.web.dto.RequestDtos.FormDataRequest;
import sn.gouv.mfp.workflow.web.security.AdminRateLimitFilter;

/**
 * API des demandes d'usagers : catalogue, formulaire servi, soumission (avec écarts de
 * validation en clair), brouillons, pièces jointes (FRM-05) et refus explicites.
 */
@WebMvcTest(RequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class RequestControllerTest {

    private static final String TYPE = "CERTIFICAT_TRAVAIL";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestSubmissionService submissions;

    @Autowired
    private RequestAttachmentService attachmentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Les simulacres sont des beans partagés par toute la classe : sans réinitialisation,
     * les stubs larges d'un test (n'importe quel argument) masqueraient ceux d'un autre.
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(submissions, attachmentService);
    }

    @TestConfiguration
    static class MockConfig {

        @Bean
        RequestSubmissionService requestSubmissionService() {
            return Mockito.mock(RequestSubmissionService.class);
        }

        @Bean
        RequestAttachmentService requestAttachmentService() {
            return Mockito.mock(RequestAttachmentService.class);
        }

        @Bean
        RequestActor requestActor() {
            return new RequestActor();
        }

        @Bean
        AdminRateLimitFilter adminRateLimitFilter() {
            return new AdminRateLimitFilter();
        }

        @Bean
        AuthenticationManagerResolver<jakarta.servlet.http.HttpServletRequest>
                jwtAuthenticationManagerResolver() {
            return Mockito.mock(AuthenticationManagerResolver.class);
        }
    }

    @Test
    void types_shouldReturnThePublishedCatalog() throws Exception {
        when(submissions.publishedTypes())
                .thenReturn(List.of(new TypeView(UUID.randomUUID(), TYPE, "Certificat de travail", "TRAVAIL")));

        mockMvc.perform(get("/api/request-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value(TYPE))
                .andExpect(jsonPath("$[0].label").value("Certificat de travail"));
    }

    @Test
    void form_shouldReturnTheActiveSchemaAndUiSchema() throws Exception {
        when(submissions.activeForm(TYPE)).thenReturn(new FormView(
                TYPE,
                "Certificat de travail",
                2,
                Map.of("type", "object", "required", List.of("nom")),
                Map.of("nom", Map.of("label", "Nom"))));

        mockMvc.perform(get("/api/request-types/{typeKey}/form", TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeKey").value(TYPE))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema.required[0]").value("nom"))
                .andExpect(jsonPath("$.uiSchema.nom.label").value("Nom"));
    }

    @Test
    void form_shouldReturn404WhenTheTypeIsNotAvailable() throws Exception {
        when(submissions.activeForm("INCONNU"))
                .thenThrow(new WorkflowNotFoundException("Aucun formulaire publié."));

        mockMvc.perform(get("/api/request-types/{typeKey}/form", "INCONNU"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WorkflowNotFoundException"));
    }

    @Test
    void submit_shouldReturn201WithTheCreatedInstance() throws Exception {
        UUID instanceId = UUID.randomUUID();
        when(submissions.submit(eq(TYPE), eq(Map.of("nom", "Awa Diop")), isNull()))
                .thenReturn(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.SUBMITTED,
                        instanceId,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        Instant.parse("2026-01-01T10:00:00Z")));

        mockMvc.perform(post("/api/requests/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FormDataRequest(TYPE, Map.of("nom", "Awa Diop")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.instanceId").value(instanceId.toString()))
                .andExpect(jsonPath("$.version").value(2));

        verify(submissions).submit(eq(TYPE), eq(Map.of("nom", "Awa Diop")), isNull());
    }

    @Test
    void submit_shouldReturn400WithTheViolations() throws Exception {
        when(submissions.submit(eq(TYPE), eq(Map.of()),
                        isNull()))
                .thenThrow(new FormSchemaValidationException(
                        "Les données de la demande ne respectent pas le formulaire publié.",
                        List.of("/nom : required property 'nom' not found")));

        mockMvc.perform(post("/api/requests/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FormDataRequest(TYPE, Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FormSchemaValidationException"))
                .andExpect(jsonPath("$.violations[0]").value("/nom : required property 'nom' not found"));
    }

    @Test
    void saveDraft_shouldReturn201() throws Exception {
        when(submissions.saveDraft(eq(TYPE), eq(Map.of("motif", "Promotion")), isNull()))
                .thenReturn(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.DRAFT,
                        null,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        null));

        mockMvc.perform(post("/api/requests/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FormDataRequest(TYPE, Map.of("motif", "Promotion")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.instanceId").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").doesNotExist());
    }

    @Test
    void drafts_shouldListTheAuthorDrafts() throws Exception {
        when(submissions.drafts(isNull()))
                .thenReturn(List.of(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.DRAFT,
                        null,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        null)));

        mockMvc.perform(get("/api/requests/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].typeKey").value(TYPE));
    }

    @Test
    void submitDraft_shouldReturn409WhenAlreadySubmitted() throws Exception {
        UUID draftId = UUID.randomUUID();
        when(submissions.submitDraft(eq(draftId), isNull()))
                .thenThrow(new WorkflowConflictException("La demande a déjà été soumise."));

        mockMvc.perform(post("/api/requests/drafts/{draftId}/submit", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WorkflowConflictException"));
    }

    @Test
    void uploadAttachment_shouldReturn201AndDelegateTheRealContent() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(attachmentService.store(
                        eq(submissionId),
                        eq("piece"),
                        eq("note.txt"),
                        eq("text/plain"),
                        eq("abc".getBytes(StandardCharsets.UTF_8)),
                        isNull()))
                .thenReturn(attachmentView(submissionId, AttachmentScanStatus.CLEAN));

        mockMvc.perform(multipart("/api/requests/submissions/{submissionId}/attachments", submissionId)
                        .file(new MockMultipartFile(
                                "file", "note.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8)))
                        .param("fieldPath", "piece"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("note.txt"))
                .andExpect(jsonPath("$.mimeType").value("text/plain"))
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"));

        verify(attachmentService).store(
                eq(submissionId),
                eq("piece"),
                eq("note.txt"),
                eq("text/plain"),
                eq("abc".getBytes(StandardCharsets.UTF_8)),
                isNull());
    }

    @Test
    void uploadAttachment_shouldReturn400WhenTheContentIsRefused() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(attachmentService.store(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowValidationException(
                        "La pièce jointe a été refusée par l'analyse antivirus et n'a pas été conservée."));

        mockMvc.perform(multipart("/api/requests/submissions/{submissionId}/attachments", submissionId)
                        .file(new MockMultipartFile("file", "virus.txt", "text/plain", "abc".getBytes()))
                        .param("fieldPath", "piece"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WorkflowValidationException"));
    }

    @Test
    void uploadAttachment_shouldReturn413WhenTooLarge() throws Exception {
        when(attachmentService.store(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPayloadTooLargeException("Pièce jointe trop volumineuse."));

        mockMvc.perform(multipart("/api/requests/submissions/{submissionId}/attachments", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "gros.pdf", "application/pdf", "abc".getBytes()))
                        .param("fieldPath", "piece"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("WorkflowPayloadTooLargeException"));
    }

    @Test
    void uploadAttachment_shouldReturn415WhenTheTypeIsNotAllowed() throws Exception {
        when(attachmentService.store(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowUnsupportedMediaTypeException(
                        "Type de fichier non autorisé : types acceptés application/pdf."));

        mockMvc.perform(multipart("/api/requests/submissions/{submissionId}/attachments", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", "abc".getBytes()))
                        .param("fieldPath", "piece"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("WorkflowUnsupportedMediaTypeException"));
    }

    @Test
    void listAttachments_shouldReturnTheVisibleAttachments() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(attachmentService.list(eq(submissionId), any()))
                .thenReturn(List.of(attachmentView(submissionId, AttachmentScanStatus.CLEAN)));

        mockMvc.perform(get("/api/requests/submissions/{submissionId}/attachments", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("note.txt"))
                .andExpect(jsonPath("$[0].sha256").value("a".repeat(64)));
    }

    @Test
    void downloadAttachment_shouldServeTheContentWithItsFingerprint() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(attachmentService.download(eq(attachmentId), any()))
                .thenReturn(new RequestAttachmentService.AttachmentDownload(
                        "note.txt",
                        "text/plain",
                        3,
                        "a".repeat(64),
                        new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));

        mockMvc.perform(get("/api/requests/attachments/{attachmentId}", attachmentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-SHA256", "a".repeat(64)))
                .andExpect(content().bytes("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void downloadAttachment_shouldReturn409WhileTheVerdictIsNotFavourable() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(attachmentService.download(eq(attachmentId), any()))
                .thenThrow(new WorkflowConflictException("analyse antivirus PENDING"));

        mockMvc.perform(get("/api/requests/attachments/{attachmentId}", attachmentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WorkflowConflictException"));
    }

    private static AttachmentView attachmentView(UUID submissionId, AttachmentScanStatus status) {
        return new AttachmentView(
                UUID.randomUUID(),
                submissionId,
                "piece",
                "note.txt",
                "text/plain",
                3,
                "a".repeat(64),
                status,
                Instant.parse("2026-01-01T10:00:00Z"));
    }
}
