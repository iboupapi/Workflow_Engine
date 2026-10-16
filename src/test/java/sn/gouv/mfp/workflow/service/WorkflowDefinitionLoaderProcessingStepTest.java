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
 * Règles d'import du marqueur {@code processingStep} et des cibles virtuelles
 * (RESPONSABLE_TRAITEMENT, ETAPE_PRECEDENTE). Test unitaire sans base : la validation
 * graphique est exercée directement (visibilité de paquet).
 */
class WorkflowDefinitionLoaderProcessingStepTest {

    private final WorkflowDefinitionLoaderService loader = new WorkflowDefinitionLoaderService(
            mock(WorkflowDefinitionRepository.class),
            mock(WorkflowStepRepository.class),
            mock(WorkflowTransitionRepository.class),
            mock(WorkflowImportAuditRepository.class),
            new ObjectMapper(),
            mock(PlatformTransactionManager.class));

    @Test
    void handlerTargetWithoutProcessingStep_shouldBeRejected() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START", null), step("B", "TASK", null), step("Z", "END", null)),
                List.of(tr("A", "VALIDER", "B"),
                        tr("B", "VALIDER", "Z"),
                        tr("B", "REJETER", "RESPONSABLE_TRAITEMENT")));

        assertThatThrownBy(() -> loader.validateGraph(config))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("processingStep");
    }

    @Test
    void moreThanOneProcessingStep_shouldBeRejected() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START", null), step("B", "TASK", true),
                        step("C", "TASK", true), step("Z", "END", null)),
                List.of(tr("A", "VALIDER", "B"), tr("B", "VALIDER", "Z")));

        assertThatThrownBy(() -> loader.validateGraph(config))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("plusieurs");
    }

    @Test
    void handlerTargetWithSingleProcessingStep_shouldBeAccepted() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START", null), step("B", "TASK", true), step("Z", "END", null)),
                List.of(tr("A", "VALIDER", "B"),
                        tr("B", "VALIDER", "Z"),
                        tr("B", "REJETER", "RESPONSABLE_TRAITEMENT")));

        assertThatCode(() -> loader.validateGraph(config)).doesNotThrowAnyException();
        assertThat(loader.validateGraph(config)).isEmpty();
    }

    @Test
    void previousStepVirtualTarget_shouldBeAcceptedWithoutAnnotation() {
        WorkflowConfigDTO config = config(
                List.of(step("A", "START", null), step("B", "TASK", null), step("Z", "END", null)),
                List.of(tr("A", "VALIDER", "B"), tr("B", "REJETER", "ETAPE_PRECEDENTE")));

        assertThatCode(() -> loader.validateGraph(config)).doesNotThrowAnyException();
    }

    private WorkflowConfigDTO config(List<StepConfigDTO> steps, List<TransitionConfigDTO> transitions) {
        return new WorkflowConfigDTO(
                new DefinitionConfigDTO("T", "T", 1), null, null, List.of(), steps, transitions);
    }

    private StepConfigDTO step(String key, String type, Boolean processingStep) {
        return new StepConfigDTO(key, key, type, null, List.of("AGENT"), processingStep);
    }

    private TransitionConfigDTO tr(String source, String action, String target) {
        return new TransitionConfigDTO(source, action, target, null);
    }
}
