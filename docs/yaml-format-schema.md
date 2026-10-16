# Schéma du format YAML natif du moteur de workflow

Référence transmise à l'équipe de recette pour la conversion des fichiers
`eservice-workflow.yaml` et `girafe-workflow.yaml`. Ce format est le **seul**
accepté par `POST /api/admin/workflows/import` : aucun adaptateur multi-syntaxe
n'est maintenu (décision assumée : deux syntaxes doubleraient la surface à tester).

Exemple complet : `src/main/resources/workflows/girafe-workflow.yaml`.

## Bloc `definition`

| Champ     | Type    | Obligatoire | Règle                                                     |
|-----------|---------|-------------|-----------------------------------------------------------|
| `key`     | string  | oui         | Clé fonctionnelle unique (ex. `GIRAFE_ACTES`)             |
| `name`    | string  | non         | Libellé lisible                                           |
| `version` | integer | non         | **Purement informatif, ignoré** : la version est attribuée par la base (max + 1) |

## Bloc `settings` (facultatif, sans valeur = garde désactivée)

| Champ                             | Type    | Effet moteur                                                     |
|-----------------------------------|---------|------------------------------------------------------------------|
| `maxActiveInstancesPerInitiator`   | integer | Plafond de dossiers ACTIVE par initiateur et par version (`> 0` pour activer, sinon 409 au-delà) |
| `resumeStrategyDefault`           | string  | `FROM_REJECTING_STEP` (défaut si absent) : après rejet, la re-validation saute directement à l'étape émettrice ; `FROM_START` : reprise linéaire normale |

## Bloc `entryPoints` (facultatif, liste vide = démarrage sur la première étape `START`)

| Champ          | Type   | Règle                                                              |
|----------------|--------|---------------------------------------------------------------------|
| `role`         | string | Rôle métier autorisé à initier depuis ce point (informatif à ce stade) |
| `startStepKey` | string | Étape de départ : `START` toujours autorisée ; `TASK` uniquement si déclarée ici, sinon 400 ; `END` toujours refusée |

## Bloc `variables` (**obligatoire**, peut être vide)

Clés de dossier autorisées dans les conditions SpEL. Toute autre clé en condition
est refusée à l'import (400). Une liste vide signifie « aucune condition autorisée ».
Étendre la liste avant d'utiliser une nouvelle clé.

## Bloc `steps` (au moins une étape `START` et une étape `END`)

| Champ                  | Type         | Règle                                                        |
|------------------------|--------------|--------------------------------------------------------------|
| `key`                  | string       | Unique dans la définition                                    |
| `name`                 | string       | Libellé lisible (facultatif)                                 |
| `type`                 | enum         | `START`, `TASK` ou `END`                                     |
| `requiredDataOnValidate` | list[string] | Données obligatoires à toute action positive sur l'étape (absente, nulle ou blanche = 400 ; jamais contrôlé sur `REJETER`) |
| `owners`               | list[string] | Rôles responsables de l'étape : boîte de réception **et** droit d'agir (`/start`, `/transition`). Absent = étape sans action possible (refus par défaut). Le rôle réservé `ADMIN` y est **interdit** (un administrateur n'est pas validateur) ; mappings métier à confirmer au §14 |
| `processingStep`       | bool         | (facultatif) Étape de traitement de référence, cible de la reprise `RESPONSABLE_TRAITEMENT` après rejet. **Au plus une** étape marquée par circuit (sinon 400) ; **obligatoire** si une transition utilise la cible `RESPONSABLE_TRAITEMENT` (sinon 400) |

## Bloc `transitions`

| Champ       | Type   | Règle                                                                                          |
|-------------|--------|-------------------------------------------------------------------------------------------------|
| `source`    | string | Doit référencer une étape déclarée, sinon 400                                                  |
| `action`    | string | Déclencheur libre (ex. `VALIDER`, `REJETER`, `TRANSMETTRE`) ; `REJETER` a un traitement dédié (mémorisation de l'étape émettrice, données non contrôlées) ; `IMPUTER` désigne la personne via la variable `assignee` (400 sans elle, traçée en `to_actor`) ; `REJET_DEFINITIF` clôt le dossier en `REJECTED_FINAL` (rejet administratif définitif, distinct de l'annulation usager `CANCELLED`) |
| `target`    | string | Étape déclarée, ou cible virtuelle : `INITIATEUR` (étape d'initiation via l'historique), `ETAPE_PRECEDENTE` (dernière étape traversée), `RESPONSABLE_TRAITEMENT` (étape de traitement de référence du circuit, `processingStep: true`) |
| `condition` | string | Expression SpEL **facultative** ; absente = transition inconditionnelle (repli évalué en dernier) |

> Il n'existe pas de bloc `outputs:` : une donnée produite en cours de circuit
> (ex. `numeroOfficiel`) est exigée via `requiredDataOnValidate` et conservée dans
> les variables du dossier ; sa relecture passe par les endpoints de lecture
> (livraison avec la section 3).

### Conditions SpEL autorisées

- Forme canonique : `#variables['cle']` (l'écriture historique `variables['cle']` est acceptée puis normalisée ; la forme canonique est validée, stockée et évaluée).
- Autorisés : littéraux, `#variables` (et lui seul : `#this`, `#root`, `@beans` refusés), accès indexés `[...]`, comparaisons (`==`, `!=`, `<`, `<=`, `>`, `>=`), logique (`and`, `or`, `!`), ternaire, Elvis.
- **Interdits** : `T(...)`, `new`, tout appel de méthode (y compris `.length()`, `.put()`), toute affectation, accès `.class`, sélections/projections de collections, opérateur `matches`, identifiants nus.
- Limites : 500 caractères, imbrication de 10 niveaux.
- À l'exécution, le contexte est en lecture seule et toute erreur d'évaluation refuse l'action (400) sans basculer sur la transition par défaut.

## Interdits structurels du YAML

Balises d'objets (`!!java...`, `!perso`, `!<uri>`, y compris sur les clés), ancres et
alias (`&`, `*`), fichiers de plus d'un million de caractères, clés dupliquées.

## Versionnage et idempotence

- Chaque contenu nouveau crée la version suivante ; les dossiers en cours gardent leur version.
- L'empreinte SHA-256 porte sur le contenu canonique (conditions normalisées, listes triées, commentaires et indentation ignorés, `version` exclu) : un réimport à l'identique renvoie HTTP 200 sans nouvelle version ; un changement réel crée N+1.
- Chaque import écrit une ligne d'audit (`workflow_import_audit`) : clé, version, auteur authentifié, horodatage UTC, empreinte, nom de fichier, contenu conservé, indicateur de création.
- L'import exige un **administrateur nommé** (JWT dont le sujet est déclaré dans `auth.admin.subjects`), sinon 403. Voir `docs/authentification.md`.
