package sn.gouv.mfp.workflow.service.attachment;

/**
 * Panne du fournisseur de stockage des pièces jointes (FRM-05).
 * <p>
 * Un dépôt qui n'a pas abouti est refusé net ({@link java.lang.RuntimeException}) plutôt
 * que consigné : aucune pièce jointe fantôme ne doit apparaître dans une demande.
 */
public class AttachmentStorageException extends RuntimeException {

    public AttachmentStorageException(String message) {
        super(message);
    }

    public AttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
