package sn.gouv.mfp.workflow.domain.definition;

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
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/** Nœud / étape d'un circuit (ex. "S_VISA", "DGFP"). */
@Entity
@Table(
        name = "workflow_step",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_step_definition_step_key",
                columnNames = {"definition_id", "step_key"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    /** Code fonctionnel de l'étape (ex. "S_VISA", "DGFP"). */
    @Column(name = "step_key", nullable = false)
    private String stepKey;

    /** Libellé lisible issu du YAML (ex. "Niveau 4 - Division S-Visa"). */
    @Column
    private String name;

    /** Nature de l'étape : point d'entrée, tâche intermédiaire ou fin de circuit. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StepType type;

    /**
     * Données obligatoires à la validation de l'étape (ex. ["numeroOfficiel"] au NUMEROTEUR),
     * issues du YAML et persistées en JSONB. Contrôlées par le moteur à chaque action
     * positive (module 6.2), jamais lors d'un rejet.
     */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> requiredData;

    /**
     * Rôles responsables de l'étape (boîte de réception, recette 3.4/3.6), issus du YAML.
     * Absent = étape invisible dans les boîtes (refus par défaut) ; mappings métier (§14)
     * à confirmer avant tout branchement réel.
     */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> owners;
}
