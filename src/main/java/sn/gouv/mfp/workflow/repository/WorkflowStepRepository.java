package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;

public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    List<WorkflowStep> findByDefinitionId(UUID definitionId);

    Optional<WorkflowStep> findByDefinitionIdAndStepKey(UUID definitionId, String stepKey);
}
