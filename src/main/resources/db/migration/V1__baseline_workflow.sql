-- ==============================================================================
-- V1 — Référence du schéma du moteur de workflow (baseline Flyway).
-- Photographie exacte du schéma construit par Hibernate (ddl-auto: update) : sur les
-- bases existantes SANS historique Flyway, cette version sert de repère de baseline
-- (baseline-on-migrate) et n'est pas rejouée ; sur les bases neuves, elle est exécutée
-- telle quelle par le compte propriétaire. Toute évolution ultérieure passe par une
-- migration versionnée (recette 12.7), jamais par ddl-auto.
-- ==============================================================================

CREATE TABLE workflow_definition (
    id uuid NOT NULL,
    definition_key varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    version integer NOT NULL,
    settings jsonb,
    entry_points jsonb,
    content_hash varchar(64),
    CONSTRAINT workflow_definition_pkey PRIMARY KEY (id),
    CONSTRAINT uk_definition_key_version UNIQUE (definition_key, version)
);

CREATE TABLE workflow_step (
    id uuid NOT NULL,
    definition_id uuid NOT NULL,
    step_key varchar(255) NOT NULL,
    name varchar(255),
    type varchar(16) NOT NULL,
    required_data jsonb,
    CONSTRAINT workflow_step_pkey PRIMARY KEY (id),
    CONSTRAINT uk_step_definition_step_key UNIQUE (definition_id, step_key),
    CONSTRAINT fk_step_definition FOREIGN KEY (definition_id)
        REFERENCES workflow_definition (id)
);

CREATE TABLE workflow_transition (
    id uuid NOT NULL,
    source_step_id uuid NOT NULL,
    target_step_key varchar(255) NOT NULL,
    action varchar(255) NOT NULL,
    condition_expression text,
    CONSTRAINT workflow_transition_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transition_source_step FOREIGN KEY (source_step_id)
        REFERENCES workflow_step (id)
);

CREATE TABLE workflow_instance (
    id uuid NOT NULL,
    definition_id uuid NOT NULL,
    current_step_key varchar(255) NOT NULL,
    initiator_id varchar(255),
    status varchar(16) NOT NULL,
    variables jsonb,
    CONSTRAINT workflow_instance_pkey PRIMARY KEY (id),
    CONSTRAINT fk_instance_definition FOREIGN KEY (definition_id)
        REFERENCES workflow_definition (id)
);

CREATE TABLE workflow_history (
    id uuid NOT NULL,
    instance_id uuid NOT NULL,
    action varchar(255) NOT NULL,
    from_step varchar(255) NOT NULL,
    to_step varchar(255) NOT NULL,
    actor_id varchar(255) NOT NULL,
    event_timestamp timestamp with time zone NOT NULL,
    comment text,
    CONSTRAINT workflow_history_pkey PRIMARY KEY (id),
    CONSTRAINT fk_history_instance FOREIGN KEY (instance_id)
        REFERENCES workflow_instance (id)
);

CREATE TABLE workflow_import_audit (
    id uuid NOT NULL,
    definition_key varchar(255) NOT NULL,
    version integer NOT NULL,
    actor_id varchar(255) NOT NULL,
    imported_at timestamp with time zone NOT NULL,
    sha256 varchar(64) NOT NULL,
    filename varchar(255),
    content text NOT NULL,
    created boolean NOT NULL,
    CONSTRAINT workflow_import_audit_pkey PRIMARY KEY (id)
);
