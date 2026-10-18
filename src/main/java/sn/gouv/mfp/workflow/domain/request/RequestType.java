package sn.gouv.mfp.workflow.domain.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Type de demande proposé à l'usager (ex. "CERTIFICAT_TRAVAIL"). Le référentiel ne porte que
 * l'identité fonctionnelle ; le contenu (formulaire et circuit) vit dans
 * {@link RequestTypeVersion}, figé à la publication.
 */
@Entity
@Table(
        name = "request_type",
        uniqueConstraints = @UniqueConstraint(name = "uk_request_type_key", columnNames = {"type_key"}))
@Getter
@Setter
@NoArgsConstructor
public class RequestType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Clé métier du type de demande. "key" étant réservé en SQL, la colonne s'appelle type_key. */
    @Column(name = "type_key", nullable = false, length = 255)
    private String key;

    /** Libellé présenté à l'usager. */
    @Column(nullable = false, length = 255)
    private String label;

    /** Catégorie de rattachement (référentiel à arrêter avec le MFP, phase 3). */
    @Column(length = 128)
    private String category;

    /** Statut courant du référentiel (contraint à DRAFT/PUBLISHED/RETIRED en base). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestTypeStatus status = RequestTypeStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
