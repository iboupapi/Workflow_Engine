-- ==============================================================================
-- V2 — Moindres privilèges et immuabilité (cahier de recette 8.1 à 8.5).
-- Exécutée par le compte propriétaire : droits minimaux du compte applicatif
-- (lecture/écriture sans DDL, sans DELETE, sans UPDATE hors workflow_instance) et
-- déclencheurs d'immuabilité sur l'historique, l'audit et les référentiels publiés.
-- Idempotente : rejouable sans effet de bord.
-- ==============================================================================

-- Droits minimaux du compte applicatif sur les tables existantes.
GRANT SELECT, INSERT ON workflow_definition TO workflow_app;
GRANT SELECT, INSERT ON workflow_step TO workflow_app;
GRANT SELECT, INSERT ON workflow_transition TO workflow_app;
GRANT SELECT, INSERT ON workflow_import_audit TO workflow_app;
GRANT SELECT, INSERT, UPDATE ON workflow_instance TO workflow_app;

-- Mêmes droits, automatiquement, sur les tables créées à l'avenir par le propriétaire.
ALTER DEFAULT PRIVILEGES FOR ROLE workflow_owner IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO workflow_app;

-- Immuabilité : seul le propriétaire peut modifier ou supprimer ces lignes.
-- Tout UPDATE ou DELETE émis par le compte applicatif (ou tout autre rôle) est rejeté
-- par le déclencheur, avant même tout contrôle applicatif.
CREATE OR REPLACE FUNCTION forbid_protected_mutation() RETURNS trigger AS $$
BEGIN
    IF current_user <> 'workflow_owner' THEN
        RAISE EXCEPTION 'Table % immuable pour le rôle % (historique et référentiels protégés).',
            TG_TABLE_NAME, current_user;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_immutable ON workflow_history;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON workflow_history
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();

DROP TRIGGER IF EXISTS trg_immutable ON workflow_import_audit;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON workflow_import_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();

DROP TRIGGER IF EXISTS trg_immutable ON workflow_definition;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON workflow_definition
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();

DROP TRIGGER IF EXISTS trg_immutable ON workflow_step;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON workflow_step
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();

DROP TRIGGER IF EXISTS trg_immutable ON workflow_transition;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON workflow_transition
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();
