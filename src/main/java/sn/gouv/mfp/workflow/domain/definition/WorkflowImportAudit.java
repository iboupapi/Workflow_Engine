package sn.gouv.mfp.workflow.domain.definition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ligne d'audit d'import de circuit (cahier de recette 7.5) : qui a déployé quelle version,
 * quand, avec quelle empreinte — y compris les réimports idempotents (created = false).
 * Écrite dans la même transaction que l'import : un import refusé ne laisse aucune trace,
 * un import accepté est toujours tracé.
 */
@Entity
@Table(name = "workflow_import_audit")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowImportAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Clé fonctionnelle du circuit importé. */
    @Column(name = "definition_key", nullable = false)
    private String definitionKey;

    /** Version créée ou réutilisée par cet import. */
    @Column(nullable = false)
    private Integer version;

    /** Identité authentifiée de l'auteur (contexte de sécurité, sinon "anonymous"). */
    @Column(name = "actor_id", nullable = false, length = 512)
    private String actorId;

    /** Horodatage UTC de l'import. */
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt = Instant.now();

    /** Empreinte SHA-256 du contenu canonique analysé (recette 7.6 / 7.7). */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** Nom du fichier d'origine (null pour un corps YAML brut). */
    @Column
    private String filename;

    /** Contenu YAML conservé tel qu'importé (recette 7.5 : fichier conservé). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Faux si le contenu existait déjà (réimport idempotent, HTTP 200). */
    @Column(nullable = false)
    private boolean created;
}
