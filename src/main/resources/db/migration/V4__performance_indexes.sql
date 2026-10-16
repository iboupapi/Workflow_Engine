-- ==============================================================================
-- V4 — Index de performance (cahier de recette 8.9 / 11.5).
-- PostgreSQL n'indexe pas les colonnes de clés étrangères : sans ces index, chaque
-- contrôle de clé, chaque jointure et chaque lecture d'historique balaye la table.
-- Idempotente (IF NOT EXISTS).
-- ==============================================================================
CREATE INDEX IF NOT EXISTS idx_history_instance_time
    ON workflow_history (instance_id, event_timestamp);
CREATE INDEX IF NOT EXISTS idx_instance_definition
    ON workflow_instance (definition_id);
CREATE INDEX IF NOT EXISTS idx_instance_status
    ON workflow_instance (status);
CREATE INDEX IF NOT EXISTS idx_step_definition
    ON workflow_step (definition_id);
CREATE INDEX IF NOT EXISTS idx_transition_source
    ON workflow_transition (source_step_id);
CREATE INDEX IF NOT EXISTS idx_audit_key_time
    ON workflow_import_audit (definition_key, imported_at DESC);
