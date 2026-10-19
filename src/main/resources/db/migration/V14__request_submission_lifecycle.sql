-- ==============================================================================
-- V14 — REQ-03 : cycle de vie d'une soumission (brouillon puis soumission).
-- V13 avait cree request_submission comme la seule trace d'une soumission deja
-- effectuee (submitted_at NOT NULL DEFAULT now(), instance_id deja renseigne).
-- REQ-03 demande en plus qu'un usager puisse ENREGISTRER UN BROUILLON, puis le
-- soumettre : la soumission valide les donnees (FRM-03), applique la liaison
-- champs -> variables (REQ-02) et cree le dossier (instance_id).
--   status        DRAFT | SUBMITTED
--   DRAFT         instance_id et submitted_at sont NULS : un brouillon n'a pas de dossier
--   SUBMITTED     instance_id et submitted_at sont renseignes, et la ligne est figee
-- Le compte applicatif peut modifier un brouillon (UPDATE), plus jamais une soumission.
-- Idempotente (IF NOT EXISTS / IF EXISTS) et rejouable sans effet de bord.
-- ==============================================================================

ALTER TABLE request_submission
    ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'DRAFT';

-- Horodatage de creation : submitted_at n'existe que pour une soumission, or un brouillon
-- doit pouvoir etre liste et repris (REQ-03).
ALTER TABLE request_submission
    ADD COLUMN IF NOT EXISTS created_at timestamp with time zone NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_submission_author_status
    ON request_submission (submitted_by, status, created_at DESC);

-- Le brouillon n'a pas d'horodatage de soumission : la valeur par defaut de V13 le
-- renseignerait a tort, et instance_id doit rester nul tant que rien n'est soumis.
ALTER TABLE request_submission ALTER COLUMN instance_id DROP NOT NULL;
ALTER TABLE request_submission ALTER COLUMN submitted_at DROP NOT NULL;
ALTER TABLE request_submission ALTER COLUMN submitted_at DROP DEFAULT;

-- Lignes deja enregistrees (aucune en pratique : V13 est de la meme livraison) : celles
-- qui portent un dossier etaient des soumissions.
UPDATE request_submission SET status = 'SUBMITTED' WHERE instance_id IS NOT NULL;

ALTER TABLE request_submission DROP CONSTRAINT IF EXISTS ck_request_submission_status;
ALTER TABLE request_submission
    ADD CONSTRAINT ck_request_submission_status CHECK (status IN ('DRAFT', 'SUBMITTED'));

-- Bijection stricte entre le statut et l'existence du dossier : un brouillon ne peut pas
-- avoir cree un dossier, une soumission ne peut pas en etre depourvue.
ALTER TABLE request_submission DROP CONSTRAINT IF EXISTS ck_request_submission_lifecycle;
ALTER TABLE request_submission
    ADD CONSTRAINT ck_request_submission_lifecycle CHECK (
        (status = 'DRAFT' AND instance_id IS NULL AND submitted_at IS NULL)
        OR (status = 'SUBMITTED' AND instance_id IS NOT NULL AND submitted_at IS NOT NULL)
    );

-- Moindres privileges : SELECT/INSERT herites des privileges par defaut (V2), UPDATE
-- uniquement pour completer un brouillon. Le DELETE n'est jamais accorde.
GRANT SELECT, INSERT, UPDATE ON request_submission TO workflow_app;

-- Immuabilite : une soumission enregistree ne se modifie plus ; dans un brouillon, seuls
-- les champs de saisie evoluent (type de demande et demandeur figes).
-- Le DELETE est impossible pour le compte applicatif, faute de privilege : seul l'UPDATE
-- doit etre surveille.
CREATE OR REPLACE FUNCTION enforce_request_submission_lifecycle() RETURNS trigger AS $$
BEGIN
    IF current_user = 'workflow_owner' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' THEN
        RAISE EXCEPTION 'Soumission % deja enregistree : une demande soumise est immuable.', OLD.id;
    END IF;
    IF NEW.request_type_version_id <> OLD.request_type_version_id THEN
        RAISE EXCEPTION 'Soumission % : la version du type de demande est immuable.', OLD.id;
    END IF;
    IF NEW.submitted_by IS DISTINCT FROM OLD.submitted_by THEN
        RAISE EXCEPTION 'Soumission % : le demandeur d un brouillon est immuable.', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_submission_lifecycle ON request_submission;
CREATE TRIGGER trg_submission_lifecycle
    BEFORE UPDATE ON request_submission
    FOR EACH ROW EXECUTE FUNCTION enforce_request_submission_lifecycle();
