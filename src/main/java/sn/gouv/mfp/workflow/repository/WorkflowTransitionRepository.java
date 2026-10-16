package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findBySourceStepId(UUID sourceStepId);

    List<WorkflowTransition> findBySourceStepIdAndAction(UUID sourceStepId, String action);
}
