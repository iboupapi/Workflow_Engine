package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    List<WorkflowDefinition> findByKey(String key);

    Optional<WorkflowDefinition> findByKeyAndVersion(String key, Integer version);

    /** Dernière version persistée pour une clé fonctionnelle (0 si absente → Optional vide). */
    @Query("SELECT MAX(d.version) FROM WorkflowDefinition d WHERE d.key = :key")
    Optional<Integer> findMaxVersionByKey(@Param("key") String key);
}
