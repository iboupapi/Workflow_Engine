package sn.gouv.mfp.workflow.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    List<WorkflowInstance> findByDefinitionId(UUID definitionId);

    List<WorkflowInstance> findByStatus(InstanceStatus status);

    List<WorkflowInstance> findByInitiatorId(String initiatorId);

    List<WorkflowInstance> findByAssigneeId(String assigneeId);

    /**
     * Nombre de dossiers ACTIVE de l'initiateur pour une définition donnée.
     * Sert le contrôle du plafond maxActiveInstancesPerInitiator (module 6.1).
     */
    long countByDefinitionIdAndInitiatorIdAndStatus(
            UUID definitionId, String initiatorId, InstanceStatus status);

    /**
     * Nombre de dossiers ACTIVE de l'initiateur pour un type d'acte donné.
     * Utilisé par le contrôle de quota « par type » (module 6.1 — règle « un projet du même type en cours »).
     * Extrait la clé 'natureActe' depuis la colonne JSONB {@code variables}.
     */
    @Query(value = """
        SELECT COUNT(i) FROM WorkflowInstance i 
        WHERE i.initiatorId = :initiatorId 
          AND i.status = 'ACTIVE' 
          AND i.definition.key = :definitionKey
          AND FUNCTION('jsonb_extract_path_text', i.variables, 'natureActe') = :natureActe
    """)
    long countActiveInstancesByInitiatorAndNatureActe(
            @Param("initiatorId") String initiatorId,
            @Param("definitionKey") String definitionKey,
            @Param("natureActe") String natureActe
    );

    /**
     * Récupère une instance en posant un verrou pessimiste en écriture (SELECT ... FOR UPDATE)
     * sur la ligne PostgreSQL : les transactions concurrentes sur le même dossier patientent
     * jusqu'à la fin de la transaction courante, ce qui prévient les race conditions
     * (double validation, par ex.). À appeler impérativement dans une transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WorkflowInstance i WHERE i.id = :id")
    Optional<WorkflowInstance> findByIdWithLock(@Param("id") UUID id);

    /**
     * Pose un verrou consultatif transactionnel PostgreSQL (MOT-05) identifié par {@code key}.
     * <p>
     * Sert à sérialiser le contrôle du plafond de dossiers actifs par rôle initiateur et par
     * type d'acte lors d'une initiation : deux initiations concurrentes du même initiateur sur
     * le même circuit patientent l'une sur l'autre, la seconde comptant alors le dossier déjà
     * créé par la première. Le verrou est libéré automatiquement à la fin de la transaction ;
     * à appeler impérativement dans une transaction.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:key)", nativeQuery = true)
    void acquireQuotaLock(@Param("key") long key);
}
