-- ==============================================================================
-- V9 — Sécurité et autorisation (Phase 1 du Cahier des charges du 17/10/2026) :
--   (1) Marqueur de traitement sets_handler sur les transitions (au lieu de l'étape)
--       permettant de mémoriser dynamiquement l'acteur et l'étape de traitement
--       en traitement délégué ou direct ;
--   (2) Périmètre organisationnel (direction, bureau) sur l'instance pour cloisonnement ;
--   (3) Traçabilité du responsable de traitement (handler_actor_id, handler_step_key)
--       posés par la transition marquée.
-- ==============================================================================

-- (1) Marqueur de transition de traitement
ALTER TABLE workflow_transition
    ADD COLUMN IF NOT EXISTS sets_handler boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN workflow_transition.sets_handler IS
    'Marqueur de traitement (setsHandler: true) : son franchissement enregistre l''acteur '
    'dans handler_actor_id et l''étape d''origine dans handler_step_key sur l''instance.';

-- (2) Colonnes de périmètre organisationnel et de responsable de traitement sur workflow_instance
ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS scope_direction varchar(128);

ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS scope_bureau varchar(128);

ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS handler_actor_id varchar(128);

ALTER TABLE workflow_instance
    ADD COLUMN IF NOT EXISTS handler_step_key varchar(64);

COMMENT ON COLUMN workflow_instance.scope_direction IS
    'Direction d''appartenance de l''initiateur, copiée du jeton JWT à la création.';

COMMENT ON COLUMN workflow_instance.scope_bureau IS
    'Bureau d''appartenance de l''initiateur, copié du jeton JWT à la création.';

COMMENT ON COLUMN workflow_instance.handler_actor_id IS
    'Identifiant de l''acteur ayant effectué le traitement de référence (posé par setsHandler).';

COMMENT ON COLUMN workflow_instance.handler_step_key IS
    'Étape où le traitement a été effectué (posée par setsHandler), cible de RESPONSABLE_TRAITEMENT.';

-- Index sur le périmètre pour accélérer la recherche et la bannette
CREATE INDEX IF NOT EXISTS idx_workflow_instance_scopes
    ON workflow_instance (scope_direction, scope_bureau);
