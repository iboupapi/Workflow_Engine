package sn.gouv.mfp.workflow.service.attachment;

import java.io.InputStream;
import java.util.UUID;

/**
 * Fournisseur de stockage du <strong>contenu</strong> des pièces jointes (FRM-05).
 * <p>
 * Le contenu ne transite jamais par la base : seule une clé opaque est consignée dans
 * {@code request_attachment}. La mise en œuvre en développement écrit sur le système de
 * fichiers local ; un stockage objet (S3/MinIO) s'y substitue sans changer le service.
 */
public interface AttachmentStorage {

    /**
     * Dépose un contenu et rend sa clé opaque.
     *
     * @param submissionId demande à laquelle la pièce est rattachée (cloisonne le stockage)
     * @param filename     nom d'origine, utilisé pour l'extension seulement
     * @param content      contenu binaire à déposer
     * @return clé opaque à consigner
     */
    String store(UUID submissionId, String filename, byte[] content);

    /** Ouvre le contenu désigné par une clé opaque. */
    InputStream load(String storageKey);

    /** Supprime un contenu déposé (refus après analyse antivirus, purge). */
    void delete(String storageKey);
}
