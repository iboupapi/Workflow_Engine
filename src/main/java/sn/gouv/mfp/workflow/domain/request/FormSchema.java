package sn.gouv.mfp.workflow.domain.request;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

/**
 * Formulaire d'un type de demande : JSON Schema 2020-12 (validation serveur, FRM-03) et
 * UI Schema (présentation), persistés en JSONB. Une version publiée de type de demande
 * référence un schéma figé : le formulaire évolue par un nouveau schéma, jamais par
 * modification en place.
 */
@Entity
@Table(name = "form_schema")
@Getter
@Setter
@NoArgsConstructor
public class FormSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** JSON Schema 2020-12 du formulaire (obligatoire : sans lui, aucune validation n'est possible). */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_schema", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> jsonSchema = new LinkedHashMap<>();

    /** UI Schema : ordre des champs, libellés, aides de saisie. Facultatif. */
    @Type(JsonType.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_schema", columnDefinition = "jsonb")
    private Map<String, Object> uiSchema;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
