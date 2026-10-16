# Socle SQL : rôles, migrations, immuabilité

Procédure d'exploitation de la base du moteur (recette §8, §12.7, §13.5).
Principe : l'application ne possède rien et ne migre rien elle-même —
elle opère avec un compte restreint, les migrations passent par le propriétaire.

## Rôles

| Rôle             | Usage                                     | Créé par              |
|------------------|-------------------------------------------|-----------------------|
| `postgres`       | Super-utilisateur, maintenance et secours | existant              |
| `workflow_owner` | Propriétaire des objets, seul à migrer    | `sql/00_roles.sql`    |
| `workflow_app`   | Compte applicatif (Spring + tests)        | `sql/00_roles.sql`    |

Droits applicatifs : `SELECT, INSERT` partout, plus `UPDATE` sur
`workflow_instance` uniquement. Ni `DELETE`, ni `UPDATE` ailleurs, ni DDL.
L'historique, l'audit et les référentiels publiés sont en outre verrouillés par
le déclencheur `trg_immutable`, qui ne laisse passer que `workflow_owner`
(8.1 à 8.4 : tout `UPDATE`/`DELETE` du compte applicatif est rejeté).

## Installation initiale

```sh
# 1. Créer la base puis les rôles (mots de passe hors du fichier, via gestionnaire
#    de secrets — jamais dans le code ni dans l'historique shell) :
psql -U postgres -d workflow_db \
  -v owner_pwd="$(cat /run/secrets/wf_owner)" \
  -v app_pwd="$(cat /run/secrets/wf_app)" \
  -f sql/00_roles.sql

# 2. Démarrer l'application avec les trois secrets :
SPRING_DATASOURCE_USERNAME=workflow_app SPRING_DATASOURCE_PASSWORD=... \
AUTH_ADMIN_SUBJECTS=matricule-admin ./mvnw spring-boot:run
# Administration par JETON : AUTH_ADMIN_SUBJECTS liste les sujets administrateurs nommés
# (aucun jeton fixe). Voir docs/authentification.md.
# L'application NE MIGRE JAMAIS (garde-fou testé : FlywayProdGuardTest) et ne
# détient jamais les identifiants du propriétaire.

# 3. Appliquer les migrations (compte propriétaire, SANS démarrer l'application) :
FLYWAY_URL=jdbc:postgresql://localhost:5432/workflow_db \
FLYWAY_OWNER_USER=workflow_owner FLYWAY_OWNER_PASSWORD=... \
./mvnw flyway:migrate
# Base neuve : V1+ exécutées. Base existante sans historique : marquage baseline
# V1 (non rejouée) puis V2+. Environnements de test : profil `migrate`
# (src/test/resources/application.yml) qui migre au démarrage du contexte.
```

## Adoption d'une base existante (créée par `ddl-auto: update`)

```sql
-- Transférer une fois la propriété des tables (super-utilisateur) AVANT V2 :
ALTER TABLE workflow_definition OWNER TO workflow_owner;
ALTER TABLE workflow_step OWNER TO workflow_owner;
ALTER TABLE workflow_transition OWNER TO workflow_owner;
ALTER TABLE workflow_instance OWNER TO workflow_owner;
ALTER TABLE workflow_history OWNER TO workflow_owner;
ALTER TABLE workflow_import_audit OWNER TO workflow_owner;
```

## Évolutions de schéma (recette 12.7)

- Nouvelle migration versionnée dans `src/main/resources/db/migration/V<N>__*.sql`,
  exécutée au démarrage par le compte propriétaire. `ddl-auto` reste à `validate`.
- Ne jamais modifier une migration déjà appliquée (empreinte Flyway) : corriger par
  une nouvelle version (exemple : `V3` complète le `GRANT` manquant de la `V2`,
  détecté par les tests sous compte applicatif).

## Maintenance du propriétaire

```sql
-- Intervention exceptionnelle (ex. purge historique) : désactiver le déclencheur
-- en tant que postgres, agir, réactiver, tracer l'opération dans le journal.
ALTER TABLE workflow_history DISABLE TRIGGER trg_immutable;
-- ... opération ...
ALTER TABLE workflow_history ENABLE TRIGGER trg_immutable;
```

## Sauvegarde (recette 8.10)

Sauvegarde logique complète, historique et audit inclus :
`pg_dump -U postgres -Fc workflow_db`. Restauration à tester sur environnement
dédié avant tout exercice de reprise.
