package sn.gouv.mfp.workflow.domain.request;

/**
 * Cycle de vie d'un type de demande (phase 3) : brouillon tant qu'il n'est pas publiable,
 * publié dès qu'une version lie formulaire et circuit, retiré lorsqu'il ne doit plus être
 * proposé (les dossiers déjà ouverts restent valides).
 */
public enum RequestTypeStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
