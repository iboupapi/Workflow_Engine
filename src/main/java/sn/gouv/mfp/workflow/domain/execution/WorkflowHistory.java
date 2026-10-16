package sn.gouv.mfp.workflow.domain.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Trace immuable d'une action exécutée sur un dossier (audit du circuit). */
@Entity
@Table(name = "workflow_history")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WorkflowInstance instance;

    /** Action exécutée (ex. "VALIDER", "REJETER"). */
    @Column(nullable = false)
    private String action;

    /** Étape de départ. */
    @Column(name = "from_step", nullable = false)
    private String fromStep;

    /** Étape d'arrivée. */
    @Column(name = "to_step", nullable = false)
    private String toStep;

    /** Identifiant de l'agent ayant exécuté l'action. */
    @Column(name = "actor_id", nullable = false)
    private String actorId;

    /**
     * Personne désignée par l'action (imputation et réimputations, recette 5.3/5.4),
     * null pour les autres actions.
     */
    @Column(name = "to_actor_id")
    private String toActorId;

    /** Date et heure exactes de l'action. Colonne event_timestamp ("timestamp" étant réservé en SQL). */
    @Column(name = "event_timestamp", nullable = false)
    private Instant timestamp = Instant.now();

    /** Motif, obligatoire en cas de rejet. */
    @Column(columnDefinition = "TEXT")
    private String comment;
}
