-- ==============================================================================
-- V6 — Lecture des dossiers (cahier de recette, section 3) : assignataire pour
-- l'imputation (lecture seule à ce stade, écriture au chantier D), horodatage de
-- création pour le tri stable de la recherche, rôles responsables par étape pour
-- la boîte de réception (appings métier à confirmer au §14).
-- ==============================================================================
ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS assignee_id varchar(255);
ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS created_at timestamp with time zone NOT NULL DEFAULT now();
ALTER TABLE workflow_step
    ADD COLUMN IF NOT EXISTS owners jsonb;
