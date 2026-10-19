package sn.gouv.mfp.workflow.domain.request;

/**
 * Verdict de l'analyse antivirus d'une pièce jointe (FRM-05).
 * <p>
 * Une pièce jointe n'est mise à disposition que si son verdict est {@link #CLEAN} : le
 * dépôt d'un fichier inconnu est traité comme suspect tant que l'analyse n'a pas abouti
 * (liste positive, jamais de mise à disposition par défaut).
 */
public enum AttachmentScanStatus {

    /** Dépôt enregistré, analyse antivirus en cours (le contenu n'est pas encore servi). */
    PENDING,

    /** Analyse favorable : la pièce jointe est disponible selon les règles du dossier. */
    CLEAN,

    /** Analyse défavorable : la pièce jointe n'est jamais mise à disposition. */
    INFECTED,

    /** Analyse impossible (moteur indisponible) : traité comme non disponible. */
    ERROR
}
