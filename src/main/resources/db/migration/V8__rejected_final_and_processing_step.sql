-- ==============================================================================
-- V8 — Décisions métier arbitrées :
--   (1) statut dédié REJECTED_FINAL pour le rejet administratif définitif
--       (action REJET_DEFINITIF, recette 6.10), distinct de l'annulation usager
--       CANCELLED ;
--   (2) marqueur d'étape de traitement (processingStep: true) servant de cible à
--       la reprise RESPONSABLE_TRAITEMENT après rejet (recette 6.5).
-- ==============================================================================

-- (1) Statut : ajout de REJECTED_FINAL, garanti par une contrainte explicite.
ALTER TABLE workflow_instance
    DROP CONSTRAINT IF EXISTS workflow_instance_status_check;
ALTER TABLE workflow_instance
    DROP CONSTRAINT IF EXISTS chk_workflow_instance_status;
ALTER TABLE workflow_instance
    ADD CONSTRAINT chk_workflow_instance_status
    CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'REJECTED_FINAL'));

COMMENT ON COLUMN workflow_instance.status IS
    'Cycle de vie : ACTIVE, COMPLETED (fin de circuit), CANCELLED (annulation usager), '
    'REJECTED_FINAL (rejet administratif définitif, recette 6.10).';

CREATE INDEX IF NOT EXISTS idx_workflow_instance_status_rejected
    ON workflow_instance (definition_id, status)
    WHERE status = 'REJECTED_FINAL';

-- (2) Étape de traitement de référence (processingStep: true) d'une définition.
ALTER TABLE workflow_step
    ADD COLUMN IF NOT EXISTS processing_step boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN workflow_step.processing_step IS
    'Étape de traitement de référence (processingStep: true) : cible de la reprise '
    'RESPONSABLE_TRAITEMENT après rejet (recette 6.5).';
