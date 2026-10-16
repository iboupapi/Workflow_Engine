package sn.gouv.mfp.workflow.domain.definition;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/**
 * Carte statique d'un circuit de validation (ex. "GIRAFE_ACTES", "E_SERVICE_DEMANDE").
 * Versionnée pour permettre l'évolution des circuits sans impacter les dossiers en cours.
 */
@Entity
@Table(
        name = "workflow_definition",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_definition_key_version",
                columnNames = {"definition_key", "version"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identifiant unique fonctionnel (ex. "GIRAFE_ACTES"). "key" étant réservé en SQL, la colonne s'appelle definition_key. */
    @Column(name = "definition_key", nullable = false)
    private String key;

    /** Version du circuit, pour gérer l'évolution des graphes. */
    @Column(nullable = false)
    private Integer version;

    /** Nom lisible du circuit. */
    @Column(nullable = false)
    private String name;

    /**
     * Empreinte SHA-256 du contenu canonique analysé (conditions normalisées, champ
     * {@code version} du YAML exclu). Sert l'idempotence des réimports (recette 7.6 / 7.7) :
     * un contenu déjà déployé ne crée pas de nouvelle version. Null pour les définitions
     * antérieures à ce mécanisme.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * Réglages globaux issus du YAML (ex. maxActiveInstancesPerInitiator, resumeStrategyDefault),
     * persistés en JSONB : aucune règle métier figée dans le schéma SQL.
     */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> settings = new HashMap<>();

    /** Points d'entrée (rôle vers étape de départ), issus du YAML, persistés en JSONB. */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<EntryPoint> entryPoints = new ArrayList<>();

    @OneToMany(mappedBy = "definition")
    private List<WorkflowStep> steps = new ArrayList<>();
}
