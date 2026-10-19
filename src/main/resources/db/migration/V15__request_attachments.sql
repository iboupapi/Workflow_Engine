-- ==============================================================================
-- V15 - FRM-05 : pieces jointes d'une demande.
-- Une piece jointe est DECRITE ici (liste de types autorises et taille maximale
-- controlees par le service, verdict antivirus, empreinte SHA-256) ; le CONTENU
-- vit hors base, sous une cle opaque (storage_key) rendue par le fournisseur de
-- stockage. Aucun binaire n'est stocke en base : les sauvegardes de la base ne
-- portent que des metadonnees, et le contenu peut etre servi par un stockage objet.
--   scan_status  PENDING   depot enregistre, analyse antivirus en cours
--                CLEAN     analyse favorable : la piece est mise a disposition
--                INFECTED  analyse defavorable : jamais mise a disposition
--                ERROR     analyse impossible : traitee comme non disponible
-- Invariant porteur : une piece jointe n'est mise a disposition que si son
-- verdict est CLEAN. La ligne est scellee des le depot (type, taille, empreinte,
-- cle de stockage, nom, chemin de champ) : seul le verdict antivirus evolue, et
-- seulement depuis PENDING (V15, declencheur trg_attachment_scan).
-- Idempotente (IF NOT EXISTS / IF EXISTS) et rejouable sans effet de bord.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS request_attachment (
    id uuid NOT NULL,
    submission_id uuid NOT NULL,
    field_path varchar(255) NOT NULL,
    filename varchar(255) NOT NULL,
    mime_type varchar(128) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256_hash varchar(64) NOT NULL,
    storage_key varchar(1024) NOT NULL,
    scan_status varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT request_attachment_pkey PRIMARY KEY (id),
    CONSTRAINT uk_attachment_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_attachment_submission FOREIGN KEY (submission_id)
        REFERENCES request_submission (id),
    CONSTRAINT ck_attachment_scan_status CHECK (
        scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR')
    ),
    -- Un fichier vide n'est pas une piece jointe (le service refuse deja 0 octet ;
    -- la base ne peut pas etre contournee par un appel direct).
    CONSTRAINT ck_attachment_size CHECK (size_bytes > 0),
    -- Empreinte SHA-256 : 64 caracteres hexadecimaux, ni plus ni moins.
    CONSTRAINT ck_attachment_sha256 CHECK (sha256_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attachment_field_path CHECK (length(btrim(field_path)) > 0)
);

-- PostgreSQL n'indexe pas les colonnes de cles etrangeres (cf. V4) : la liste des
-- pieces jointes d'une demande est lue par ce chemin.
CREATE INDEX IF NOT EXISTS idx_attachment_submission
    ON request_attachment (submission_id, created_at);

-- Moindres privileges : SELECT/INSERT herites des privileges par defaut (V2) ; le
-- UPDATE n'est accorde que pour porter le verdict antivirus. Le DELETE n'est jamais
-- accorde : une piece jointe deposee reste tracee, meme infectee.
GRANT SELECT, INSERT, UPDATE ON request_attachment TO workflow_app;

-- Scellement : au depot, tout est fige (demande, champ, nom, type, taille, empreinte,
-- cle de stockage). Seul le verdict antivirus peut etre pose, une seule fois, et
-- uniquement a partir de PENDING : un verdict favorable ne peut pas etre rejoue ni
-- transforme en verdict defavorable apres mise a disposition.
CREATE OR REPLACE FUNCTION enforce_attachment_seal() RETURNS trigger AS $$
BEGIN
    IF current_user = 'workflow_owner' THEN
        -- Le proprietaire peut purger (droit a l'effacement, RGPD) : un BEFORE DELETE
        -- doit retourner OLD, pas NEW (retourner NULL annulerait la suppression).
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Piece jointe % : une piece jointe deposee ne se supprime pas.', OLD.id;
    END IF;
    IF NEW.submission_id IS DISTINCT FROM OLD.submission_id
        OR NEW.field_path IS DISTINCT FROM OLD.field_path
        OR NEW.filename IS DISTINCT FROM OLD.filename
        OR NEW.mime_type IS DISTINCT FROM OLD.mime_type
        OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
        OR NEW.sha256_hash IS DISTINCT FROM OLD.sha256_hash
        OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Piece jointe % : le depot est scelle, seul le verdict antivirus evolue.', OLD.id;
    END IF;
    IF OLD.scan_status <> 'PENDING' AND NEW.scan_status IS DISTINCT FROM OLD.scan_status THEN
        RAISE EXCEPTION 'Piece jointe % : le verdict antivirus % est deja pose.', OLD.id, OLD.scan_status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_attachment_scan ON request_attachment;
CREATE TRIGGER trg_attachment_scan
    BEFORE UPDATE OR DELETE ON request_attachment
    FOR EACH ROW EXECUTE FUNCTION enforce_attachment_seal();
