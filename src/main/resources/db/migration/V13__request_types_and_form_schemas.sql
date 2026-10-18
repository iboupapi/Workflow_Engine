-- ==============================================================================
-- V13 — FRM / REQ (phase 3) : types de demande et schemas de formulaire.
-- Quatre tables :
--   request_type          referentiel des types de demande (cle metier, libelle, categorie, statut)
--   form_schema           JSON Schema 2020-12 + UI Schema (donnees de configuration, en JSONB)
--   request_type_version  version immuable liant un type, un schema de formulaire et une
--                         definition de circuit, plus la liaison champs -> variables (REQ-02)
--   request_submission    donnees de saisie d'un usager (form_data JSONB) rattachees a un dossier
-- Note de version : la phase 3 avait ete annoncee sous le numero V11, deja pris par
-- V11__widen_identity_columns ; cette migration est donc la V13.
-- Idempotente (IF NOT EXISTS) et rejouable sans effet de bord.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS request_type (
    id uuid NOT NULL,
    type_key varchar(255) NOT NULL,
    label varchar(255) NOT NULL,
    category varchar(128),
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT request_type_pkey PRIMARY KEY (id),
    CONSTRAINT uk_request_type_key UNIQUE (type_key),
    CONSTRAINT ck_request_type_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

-- Le schéma de formulaire n'a pas de clé métier : il est adressé par son identifiant,
-- la version du type de demande le figeant au moment de sa publication.
CREATE TABLE IF NOT EXISTS form_schema (
    id uuid NOT NULL,
    json_schema jsonb NOT NULL,
    ui_schema jsonb,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT form_schema_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS request_type_version (
    id uuid NOT NULL,
    request_type_id uuid NOT NULL,
    version integer NOT NULL,
    form_schema_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    field_mapping jsonb,
    published_at timestamp with time zone,
    CONSTRAINT request_type_version_pkey PRIMARY KEY (id),
    CONSTRAINT uk_request_type_version UNIQUE (request_type_id, version),
    CONSTRAINT fk_rtv_request_type FOREIGN KEY (request_type_id)
        REFERENCES request_type (id),
    CONSTRAINT fk_rtv_form_schema FOREIGN KEY (form_schema_id)
        REFERENCES form_schema (id),
    CONSTRAINT fk_rtv_definition FOREIGN KEY (definition_id)
        REFERENCES workflow_definition (id)
);

CREATE TABLE IF NOT EXISTS request_submission (
    id uuid NOT NULL,
    request_type_version_id uuid NOT NULL,
    instance_id uuid,
    form_data jsonb NOT NULL,
    submitted_by varchar(512),
    submitted_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT request_submission_pkey PRIMARY KEY (id),
    CONSTRAINT fk_submission_version FOREIGN KEY (request_type_version_id)
        REFERENCES request_type_version (id),
    CONSTRAINT fk_submission_instance FOREIGN KEY (instance_id)
        REFERENCES workflow_instance (id)
);

-- PostgreSQL n'indexe pas les colonnes de cles etrangeres (cf. V4).
CREATE INDEX IF NOT EXISTS idx_rtv_request_type ON request_type_version (request_type_id);
CREATE INDEX IF NOT EXISTS idx_rtv_form_schema ON request_type_version (form_schema_id);
CREATE INDEX IF NOT EXISTS idx_rtv_definition ON request_type_version (definition_id);
CREATE INDEX IF NOT EXISTS idx_submission_version ON request_submission (request_type_version_id);
CREATE INDEX IF NOT EXISTS idx_submission_instance ON request_submission (instance_id);
CREATE INDEX IF NOT EXISTS idx_request_type_status ON request_type (status);

-- Droits du compte applicatif : SELECT/INSERT hérités des privilèges par défaut (V2) ;
-- seul le statut d'un type de demande doit pouvoir évoluer (DRAFT -> PUBLISHED -> RETIRED).
GRANT SELECT, INSERT, UPDATE ON request_type TO workflow_app;

-- Version publiée = immuable : ni UPDATE ni DELETE pour le compte applicatif (V2, 8.1).
DROP TRIGGER IF EXISTS trg_immutable ON request_type_version;
CREATE TRIGGER trg_immutable
    BEFORE UPDATE OR DELETE ON request_type_version
    FOR EACH ROW EXECUTE FUNCTION forbid_protected_mutation();
