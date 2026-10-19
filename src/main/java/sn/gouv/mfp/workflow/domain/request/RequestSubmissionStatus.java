package sn.gouv.mfp.workflow.domain.request;

/**
 * Cycle de vie d'une soumission (REQ-03) : un usager peut enregistrer un brouillon, le
 * reprendre, puis le soumettre. La soumission est définitive : elle crée le dossier du
 * circuit et n'est plus modifiable.
 */
public enum RequestSubmissionStatus {

    /** Saisie en cours, sans dossier : reprenable et modifiable par son auteur. */
    DRAFT,

    /** Demande soumise : validée, liée à un dossier et figée. */
    SUBMITTED
}
