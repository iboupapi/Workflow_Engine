-- ==============================================================================
-- Socle SQL : création des rôles (cahier de recette, section 8).
-- À exécuter UNE FOIS en tant que super-utilisateur (postgres). Les mots de passe
-- ne figurent JAMAIS dans ce fichier : les passer en variables psql depuis un
-- gestionnaire de secrets, par exemple :
--   psql -U postgres -d workflow_db -v owner_pwd="$(cat /run/secrets/wf_owner)" \
--          -v app_pwd="$(cat /run/secrets/wf_app)" -f sql/00_roles.sql
-- ==============================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'workflow_owner') THEN
        CREATE ROLE workflow_owner WITH LOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'workflow_app') THEN
        CREATE ROLE workflow_app WITH LOGIN;
    END IF;
END
$$;

ALTER ROLE workflow_owner WITH PASSWORD :'owner_pwd';
ALTER ROLE workflow_app WITH PASSWORD :'app_pwd';

GRANT CONNECT ON DATABASE workflow_db TO workflow_owner, workflow_app;
GRANT USAGE ON SCHEMA public TO workflow_owner, workflow_app;
-- Le propriétaire applique les migrations (table d'historique Flyway incluse).
GRANT CREATE ON SCHEMA public TO workflow_owner;
