-- ==============================================================================
-- V11 — Identités préfixées par l'émetteur (SEC-03) : l'acteur d'un jeton est
-- désormais « nomEmetteur:sub » (nom d'émetteur = clé de configuration, ex.
-- « agents:agent-001 »). On élargit les colonnes d'identité pour absorber ce
-- préfixe sans troncature (les anciennes lignes conservent l'ancien format,
-- l'historique étant immuable : aucune réécriture).
-- ==============================================================================

-- workflow_instance : initiateur, assigné, responsable de traitement
ALTER TABLE workflow_instance
    ALTER COLUMN initiator_id TYPE varchar(512);

ALTER TABLE workflow_instance
    ALTER COLUMN assignee_id TYPE varchar(512);

ALTER TABLE workflow_instance
    ALTER COLUMN handler_actor_id TYPE varchar(512);

-- workflow_history : acteur et personne désignée (imputation)
ALTER TABLE workflow_history
    ALTER COLUMN actor_id TYPE varchar(512);

ALTER TABLE workflow_history
    ALTER COLUMN to_actor_id TYPE varchar(512);

-- workflow_import_audit : auteur authentifié de l'import
ALTER TABLE workflow_import_audit
    ALTER COLUMN actor_id TYPE varchar(512);

COMMENT ON COLUMN workflow_instance.initiator_id IS
    'Identité de l''initiateur, préfixée par l''émetteur (nomEmetteur:sub) depuis SEC-03.';
COMMENT ON COLUMN workflow_history.actor_id IS
    'Identité de l''acteur, préfixée par l''émetteur (nomEmetteur:sub) depuis SEC-03 ; '
    'les lignes antérieures portent l''ancien format (sub seul).';
