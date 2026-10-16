-- ==============================================================================
-- V3 — Complète la V2 : GRANT INSERT manquant sur workflow_history.
-- Détecté par les tests sous compte applicatif (l'historique, écrit à chaque
-- transition, exige SELECT + INSERT). Les UPDATE/DELETE restent interdits
-- (absence de GRANT + déclencheur trg_immutable).
-- ==============================================================================
GRANT SELECT, INSERT ON workflow_history TO workflow_app;
