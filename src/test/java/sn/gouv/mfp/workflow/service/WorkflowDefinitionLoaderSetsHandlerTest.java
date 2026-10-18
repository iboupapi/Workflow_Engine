package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowImportAuditRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.DefinitionConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.StepConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.TransitionConfigDTO;

/**
 * Règles d'import du marqueur de traitement {@code setsHandler} (porté par la transition,
 * plus par l'étape) et des cibles virtuelles (RESPONSABLE_TRAITEMENT, ETAPE_PRECEDENTE).
 * Test unitaire sans base : la validation graphique est exercée directement (visibilité
 * de paquet).
 */
class WorkflowDefinitionLoaderSetsHandlerTest {

    private final WorkflowDefinitionLoaderService loader = new WorkflowDefinitionLoaderService(
            mock(WorkflowDefinitionRepository.class),
            mock(WorkflowStepRepository.class),
            mock(WorkflowTransitionRepository.class),
            mock(WorkflowImportAuditRepository.class),
            new ObjectMapper(),
            mock(PlatformTransactionManager.class));

    @Test
    void handlerTargetWithoutSetsHandler_shouldBeRejected() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START"), step("B", "TASK"), step("Z", "END")),
                List.of(tr("A", "VALIDER", "B"),
                        tr("B", "VALIDER", "Z"),
                        tr("B", "REJETER", "RESPONSABLE_TRAITEMENT")));

        assertThatThrownBy(() -> loader.validateGraph(config))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("setsHandler");
    }

    @Test
    void handlerTargetWithSetsHandlerTransition_shouldBeAccepted() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START"), step("B", "TASK"), step("Z", "END")),
                List.of(tr("A", "VALIDER", "B"),
                        trHandler("B", "VALIDER", "Z"),
                        tr("B", "REJETER", "RESPONSABLE_TRAITEMENT")));

        assertThatCode(() -> loader.validateGraph(config)).doesNotThrowAnyException();
        assertThat(loader.validateGraph(config)).isEmpty();
    }

    @Test
    void multipleSetsHandlerTransitions_shouldBeAccepted() {
        // Traitement délégué ET traitement direct (E-Service) : deux transitions marquées valides.
        WorkflowConfigDTO config = config(
                List.of(step("A", "START"), step("B", "TASK"), step("C", "TASK"), step("Z", "END")),
                List.of(tr("A", "VALIDER", "B"),
                        trHandler("B", "VALIDER", "C"),
                        trHandler("C", "VALIDER", "Z"),
                        tr("C", "REJETER", "RESPONSABLE_TRAITEMENT")));

        assertThatCode(() -> loader.validateGraph(config)).doesNotThrowAnyException();
    }

    @Test
    void previousStepVirtualTarget_shouldBeAcceptedWithoutAnnotation() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START"), step("B", "TASK"), step("Z", "END")),
                List.of(tr("A", "VALIDER", "B"), tr("B", "REJETER", "ETAPE_PRECEDENTE")));

        assertThatCode(() -> loader.validateGraph(config)).doesNotThrowAnyException();
    }

    private WorkflowConfigDTO config(List<StepConfigDTO> steps, List<TransitionConfigDTO> transitions) {
        return new WorkflowConfigDTO(
                new DefinitionConfigDTO("T", "T", 1), null, null, List.of(), steps, transitions);
    }

    private StepConfigDTO step(String key, String type) {
        return new StepConfigDTO(key, key, type, null, List.of("AGENT"));
    }

    private TransitionConfigDTO tr(String source, String action, String target) {
        return new TransitionConfigDTO(source, action, target, null);
    }

    private TransitionConfigDTO trHandler(String source, String action, String target) {
        return new TransitionConfigDTO(source, action, target, null, true);
    }
}
