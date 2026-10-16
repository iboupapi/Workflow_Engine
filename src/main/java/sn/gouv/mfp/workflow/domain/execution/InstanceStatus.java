package sn.gouv.mfp.workflow.domain.execution;

/** Cycle de vie d'un dossier en cours de circulation. */
public enum InstanceStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    /** Rejet administratif définitif (action REJET_DEFINITIF, recette 6.10) : distinct de l'annulation usager. */
    REJECTED_FINAL
}
