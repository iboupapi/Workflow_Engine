package sn.gouv.mfp.workflow.domain.request;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
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
 * Version publiée d'un type de demande : elle fige, pour un type donné, le formulaire
 * (schéma JSON), le circuit qui traitera la demande et la liaison des champs saisis vers
 * les variables de décision (REQ-02).
 * <p>
 * Cette ligne est <strong>immuable</strong> en base (déclencheur d'immuabilité, V13) : un
 * formulaire ou un circuit évolue par une nouvelle version, jamais par modification en
 * place. Les dossiers déjà soumis restent ainsi interprétables (FRM-04).
 */
@Entity
@Table(
        name = "request_type_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_request_type_version",
                columnNames = {"request_type_id", "version"}))
@Getter
@Setter
@NoArgsConstructor
public class RequestTypeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Type de demande dont ceci est une version. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_type_id", nullable = false)
    private RequestType requestType;

    /** Numéro de version, croissant par type de demande. */
    @Column(nullable = false)
    private Integer version;

    /** Formulaire de saisie de cette version (FRM-03, FRM-04). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_schema_id", nullable = false)
    private FormSchema formSchema;

    /** Circuit de traitement : le dossier créé à la soumission suivra cette définition. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    /**
     * Liaison champs du formulaire → variables du dossier (REQ-02), par exemple
     * {@code {"typeActe": "natureActe"}}. Nulle ou vide : aucune variable n'est dérivée
     * de la saisie.
     */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_mapping", columnDefinition = "jsonb")
    private Map<String, Object> fieldMapping;

    /**
     * Horodatage de publication. Une version non publiée (nulle) n'est jamais servie aux
     * usagers ni utilisée pour une soumission.
     */
    @Column(name = "published_at")
    private Instant publishedAt;
}
