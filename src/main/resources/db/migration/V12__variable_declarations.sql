-- ==============================================================================
-- V12 — MOT-10 : déclarations de variables de dossier portées par la définition.
-- Le bloc YAML 'variables:' passe d'une liste de clés à des objets {key, type, required} ;
-- les déclarations sont persistées en JSONB (donnée de configuration, jamais figée en SQL).
-- Les définitions antérieures gardent NULL = aucune variable obligatoire (rétro-compatible).
-- ==============================================================================
ALTER TABLE workflow_definition
    ADD COLUMN IF NOT EXISTS variable_declarations jsonb;
