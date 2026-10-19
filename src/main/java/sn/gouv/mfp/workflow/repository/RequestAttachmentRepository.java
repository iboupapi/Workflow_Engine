package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.request.RequestAttachment;

/** Accès aux pièces jointes d'une demande (FRM-05). */
public interface RequestAttachmentRepository extends JpaRepository<RequestAttachment, UUID> {

    /** Pièces jointes d'une demande, dans l'ordre de dépôt. */
    List<RequestAttachment> findBySubmissionIdOrderByCreatedAtAsc(UUID submissionId);
}
