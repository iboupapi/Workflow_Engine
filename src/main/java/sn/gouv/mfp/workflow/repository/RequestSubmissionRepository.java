package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;

public interface RequestSubmissionRepository extends JpaRepository<RequestSubmission, UUID> {

    /**
     * Saisies d'un demandeur pour un statut donné, de la plus récente à la plus ancienne :
     * ce que l'usager peut reprendre (brouillons) ou consulter (demandes soumises).
     */
    List<RequestSubmission> findBySubmittedByAndStatusOrderByCreatedAtDesc(
            String submittedBy, RequestSubmissionStatus status);

    /** Soumission définitive ayant créé un dossier (traçabilité depuis le dossier). */
    Optional<RequestSubmission> findByInstanceId(UUID instanceId);
}
