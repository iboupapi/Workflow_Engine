package sn.gouv.mfp.workflow.domain.definition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Chemin orienté entre deux étapes, déclenché par une action.
 * La règle de routage est une expression SpEL optionnelle évaluée contre les variables du dossier :
 * si null, la transition est inconditionnelle.
 */
@Entity
@Table(name = "workflow_transition")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Étape d'origine du chemin. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_step_id", nullable = false)
    private WorkflowStep sourceStep;

    /** Code de l'étape de destination (ex. "DGFP"). */
    @Column(name = "target_step_key", nullable = false)
    private String targetStepKey;

    /** Déclencheur de la transition (ex. "VALIDER", "REJETER"). */
    @Column(nullable = false)
    private String action;

    /**
     * Expression SpEL optionnelle (ex. "#variables['circuit'] == 'COURT'").
     * Null = transition inconditionnelle.
     */
    @Column(name = "condition_expression", columnDefinition = "TEXT")
    private String conditionExpression;
}
