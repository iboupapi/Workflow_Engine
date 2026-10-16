package sn.gouv.mfp.workflow.domain.execution;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;

/**
 * Dossier en cours de circulation : position actuelle sur le circuit + données métier arbitraires.
 * Le champ {@code variables} (JSONB) accueille n'importe quelle donnée injectée par les
 * applications clientes (nature de l'acte, numéro officiel, circuit, ...) sans figer de
 * règles métier dans le schéma SQL.
 */
@Entity
@Table(name = "workflow_instance")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Circuit suivi par ce dossier. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    /** Code de l'étape où se trouve le dossier (ex. "S_VISA"). */
    @Column(name = "current_step_key", nullable = false)
    private String currentStepKey;

    /** Identifiant de l'agent ayant initié le dossier (ex. matricule). Nullable pour compatibilité avec les dossiers pré-existants. */
    @Column(name = "initiator_id")
    private String initiatorId;

    /**
     * Identifiant de la personne à qui le dossier est imputé (lecture seule à ce stade :
     * l'écriture viendra avec l'action IMPUTER du chantier D). Un dossier imputé sort
     * de la boîte collective du rôle : seul l'assigné (plus initiateur et admin) le voit.
     */
    @Column(name = "assignee_id")
    private String assigneeId;

    /** Horodatage UTC de création, pour le tri stable de la recherche. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstanceStatus status = InstanceStatus.ACTIVE;

    /**
     * Cœur dynamique du dossier, persisté en JSONB.
     * {@code @Type(JsonType.class)} assure le mapping Map &lt;-&gt; JSONB via hypersistence-utils,
     * {@code @JdbcTypeCode(SqlTypes.JSON)} garantit la compatibilité native Hibernate 7.
     */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> variables = new HashMap<>();
}
