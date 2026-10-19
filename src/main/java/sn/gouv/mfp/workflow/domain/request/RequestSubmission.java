package sn.gouv.mfp.workflow.domain.request;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;

/**
 * Saisie d'un usager sur une version de type de demande (REQ-03) : brouillon reprenable,
 * puis soumission définitive qui crée le dossier du circuit.
 * <p>
 * Le statut et l'existence du dossier sont liés en base : un {@link RequestSubmissionStatus#DRAFT}
 * n'a ni dossier ni horodatage de soumission, une soumission {@link
 * RequestSubmissionStatus#SUBMITTED} a les deux et devient immuable (V14).
 */
@Entity
@Table(name = "request_submission")
@Getter
@Setter
@NoArgsConstructor
public class RequestSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Version de type de demande sur laquelle porte la saisie (formulaire et circuit figés). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_type_version_id", nullable = false)
    private RequestTypeVersion requestTypeVersion;

    /** Dossier créé par la soumission ; nul tant que la saisie est un brouillon. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id")
    private WorkflowInstance instance;

    /** Données saisies, telles que validées par le schéma du formulaire. */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> formData = new LinkedHashMap<>();

    /** Identité certifiée du demandeur (format nomEmetteur:sub, SEC-03). */
    @Column(name = "submitted_by", length = 512)
    private String submittedBy;

    /** Horodatage de la soumission ; nul tant que la saisie est un brouillon. */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** Horodatage de création de la saisie : repère des brouillons, qui n'ont pas de soumission. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestSubmissionStatus status = RequestSubmissionStatus.DRAFT;

    /** Brouillon reprenable par son auteur. */
    public boolean isDraft() {
        return status == RequestSubmissionStatus.DRAFT;
    }
}
