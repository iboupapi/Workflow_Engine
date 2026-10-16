package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.execution.WorkflowHistory;

public interface WorkflowHistoryRepository extends JpaRepository<WorkflowHistory, UUID> {

    List<WorkflowHistory> findByInstanceIdOrderByTimestampAsc(UUID instanceId);
}
