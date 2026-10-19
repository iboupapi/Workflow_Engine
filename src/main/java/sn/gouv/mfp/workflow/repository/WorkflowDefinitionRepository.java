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

    /**
     * Dernière version de CHAQUE clé fonctionnelle, triée par clé : ce que l'administration
     * peut rattacher à un type de demande (les dossiers démarrent toujours sur la dernière
     * version, comme {@code WorkflowDefinitionViewService}).
     */
    @Query("SELECT d FROM WorkflowDefinition d WHERE d.version = "
            + "(SELECT MAX(x.version) FROM WorkflowDefinition x WHERE x.key = d.key) "
            + "ORDER BY d.key")
    List<WorkflowDefinition> findLatestVersionPerKey();
}
