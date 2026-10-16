-- ==============================================================================
-- V7 — Traçabilité de l'imputation (chantier D, recette 5.3/5.4) : la personne à
-- qui le dossier est imputé est conservée sur chaque ligne d'historique
-- (qui a imputé à qui, y compris lors des réimputations successives).
-- ==============================================================================
ALTER TABLE workflow_history
    ADD COLUMN IF NOT EXISTS to_actor_id varchar(255);
