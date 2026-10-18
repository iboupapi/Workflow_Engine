# Console de test du moteur de workflow (Phase 2)

Interface React pour piloter le circuit E-Service pendant la recette : consulter les
dossiers, visualiser la carte d'un circuit et créer une demande.

## Prérequis

Backend démarré en profil `dev` (voir `../src/main/resources/application-dev.yml`) :

- `TEST_ISSUER_SECRET` défini — sans lui le backend refuse de démarrer ;
- circuit importé via `POST /api/admin/workflows/import` (aucun import automatique) ;
- émetteur HMAC local exposant `GET /api/dev/tokens` (un jeton de test par rôle moteur).

## Commandes

- `npm install` — installe les dépendances ;
- `npm run dev` — console sur http://localhost:5173 ;
- `npm run build` — vérification des types (strict) puis build de production ;
- `npm run lint` — oxlint ;
- `npm run api:generate` — régénère `src/api/schema.d.ts` depuis `/v3/api-docs`
  (backend démarré avec `SWAGGER_ENABLED=true`).

`src/api/types.ts` **dérive ses types de `schema.d.ts`** : après régénération du schéma,
une recompilation révèle toute rupture de contrat avec le moteur.

## Utilisation

1. Choisir un jeton de test dans la barre supérieure (CITOYEN, CHEF_DIVISION,
   AGENT_TRAITEMENT, DLEC, DGFP…).
2. **Dossiers** — bannettes `MINE` / `INBOX` (`ALL` est réservé aux administrateurs et
   auditeurs), détail du dossier, actions possibles et historique.
3. **Circuit** — carte statique du circuit (étapes, rôles responsables, transitions).
4. **Nouvelle demande** — création d'un dossier sur un point d'entrée du circuit.

L'imputation (`IMPUTER`) exige la variable `assignee` portant l'**identité certifiée** de
l'agent (ex. `test:dev-agent_traitement`) ; le champ JSON du détail sert à la fournir.
