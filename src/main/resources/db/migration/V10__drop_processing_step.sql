-- ==============================================================================
-- V10 — Consolidation Phase 1 (Cahier des charges du 17/10/2026) :
--   le marqueur de traitement est désormais porté par la TRANSITION
--   (workflow_transition.sets_handler, migration V9), plus par l'étape
--   (workflow_step.processing_step, migration V8). La colonne d'étape est retirée :
--   elle ne portait aucune donnée métier, uniquement un marqueur de configuration
--   désormais remplacé par sets_handler (MOT-06).
-- ==============================================================================

ALTER TABLE workflow_step
    DROP COLUMN IF EXISTS processing_step;
