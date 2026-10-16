-- ==============================================================================
-- V5 — Lisibilité de l'historique des migrations pour le compte applicatif
-- (supervision et tests du socle : versions appliquées visibles sans privilège
-- d'écriture).
-- ==============================================================================
GRANT SELECT ON flyway_schema_history TO workflow_app;
