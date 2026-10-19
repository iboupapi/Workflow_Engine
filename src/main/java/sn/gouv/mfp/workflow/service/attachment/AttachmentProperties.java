package sn.gouv.mfp.workflow.service.attachment;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres des pièces jointes (FRM-05) : liste de types autorisés, taille maximale et
 * répertoire de stockage local.
 * <p>
 * La liste de types est une liste <strong>positive</strong> portant sur le type MIME
 * <em>réel</em> (déduit du contenu) ; une valeur vide est remplacée par la liste par défaut,
 * jamais par « tout est autorisé ».
 */
@ConfigurationProperties(prefix = "workflow.attachments")
public record AttachmentProperties(
        long maxSizeBytes,
        List<String> allowedMimeTypes,
        String directory) {

    /** Liste par défaut : documents et images usuels d'un dossier administratif. */
    public static final List<String> DEFAULT_MIME_TYPES = List.of(
            ContentTypeSniffer.PDF,
            ContentTypeSniffer.PNG,
            ContentTypeSniffer.JPEG,
            ContentTypeSniffer.TEXT);

    /** Taille maximale par défaut : 5 Mio (pièce scannée courante). */
    public static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    public AttachmentProperties {
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
            allowedMimeTypes = DEFAULT_MIME_TYPES;
        }
        if (directory == null || directory.isBlank()) {
            directory = java.nio.file.Path
                    .of(System.getProperty("java.io.tmpdir"), "workflow-attachments")
                    .toString();
        }
    }

    /** Type MIME réel autorisé au dépôt. */
    public boolean allows(String mimeType) {
        return allowedMimeTypes.contains(mimeType);
    }
}
