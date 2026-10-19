package sn.gouv.mfp.workflow.domain.request;

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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Pièce jointe d'une demande (FRM-05) : le contenu vit hors base sous une clé opaque,
 * cette ligne ne portant que les métadonnées contrôlées et le verdict antivirus.
 * <p>
 * Le type MIME consigné est le <strong>type réel</strong> déduit du contenu (et non
 * l'en-tête déclaré par le client, qui n'est qu'une intention), la taille est celle
 * effectivement reçue, et l'empreinte SHA-256 identifie le fichier déposé.
 */
@Entity
@Table(name = "request_attachment")
@Getter
@Setter
@NoArgsConstructor
public class RequestAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Demande (brouillon ou soumission) à laquelle la pièce est rattachée. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private RequestSubmission submission;

    /** Champ de formulaire que la pièce renseigne (chemin dans le schéma). */
    @Column(name = "field_path", nullable = false, length = 255)
    private String fieldPath;

    /** Nom d'origine du fichier, tel que déposé par l'usager. */
    @Column(nullable = false, length = 255)
    private String filename;

    /** Type MIME réel, déduit du contenu (jamais celui déclaré par le client). */
    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    /** Taille du contenu déposé, en octets. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Empreinte SHA-256 du contenu (hexadécimal minuscule, 64 caractères). */
    @Column(name = "sha256_hash", nullable = false, length = 64)
    private String sha256Hash;

    /** Clé opaque du contenu chez le fournisseur de stockage. */
    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    /** Verdict antivirus : seule valeur modifiable après le dépôt (V15). */
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 16)
    private AttachmentScanStatus scanStatus = AttachmentScanStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Pièce jointe mise à disposition (analyse favorable). */
    public boolean isAvailable() {
        return scanStatus == AttachmentScanStatus.CLEAN;
    }
}
