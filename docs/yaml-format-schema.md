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
| `maxActiveInstancesPerType`        | integer | Plafond de dossiers ACTIVE par initiateur **et par type d'acte** (variable `natureActe`) : `> 0` autorise un seul dossier en cours par nature ; combinable avec le plafond par initiateur |
| `activeInstanceLimits`             | liste   | **MOT-05** — plafond de dossiers actifs par **rôle initiateur** ET par type d'acte, appliqué sous verrou. Liste d'entrées `{ role, maxPerType, maxPerInitiator }` : la règle ne s'applique qu'aux initiateurs portant `role` (ex. seul `AGENT_BUREAU`, les chefs ne sont pas plafonnés). Cumulé au plus restrictif avec les deux plafonds ci-dessus |
| `resumeStrategyDefault`           | string  | `FROM_REJECTING_STEP` (défaut si absent) : après rejet, la re-validation saute directement à l'étape émettrice ; `FROM_START` : reprise linéaire normale |

Exemple de plafond scopé par rôle (GIRAFE — un agent de bureau ne peut pas avoir deux dossiers
en cours de la même nature d'acte ; les chefs de bureau et de division restent libres) :

```yaml
settings:
  activeInstanceLimits:
    - role: "AGENT_BUREAU"
      maxPerType: 1
  resumeStrategyDefault: "FROM_START"
```

## Bloc `entryPoints` (facultatif, liste vide = démarrage sur la première étape `START`)

| Champ          | Type   | Règle                                                              |
|----------------|--------|---------------------------------------------------------------------|
| `role`         | string | Rôle métier autorisé à initier depuis ce point : le demandeur doit le porter (sinon 403). Voir `condition` |
| `startStepKey` | string | Étape de départ (alias accepté : `step`) : `START` toujours autorisée ; `TASK` uniquement si déclarée ici, sinon 400 ; `END` toujours refusée |
| `condition`    | string | (facultatif) Condition SpEL d'éligibilité (même liste blanche que les transitions, ex. le cas retraite). Évaluée à `/start` pour le rôle du point d'entrée ; condition non satisfaite = 403 |

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

## Bloc `transitions`

| Champ       | Type   | Règle                                                                                          |
|-------------|--------|-------------------------------------------------------------------------------------------------|
| `source`    | string | Doit référencer une étape déclarée, sinon 400                                                  |
| `action`    | string | Déclencheur libre (ex. `VALIDER`, `REJETER`, `TRANSMETTRE`) ; `REJETER` a un traitement dédié (mémorisation de l'étape émettrice, données non contrôlées) ; `IMPUTER` désigne la personne via la variable `assignee` (400 sans elle, traçée en `to_actor`) ; `REJET_DEFINITIF` clôt le dossier en `REJECTED_FINAL` (rejet administratif définitif, distinct de l'annulation usager `CANCELLED`) |
| `target`    | string | Étape déclarée, ou cible virtuelle : `INITIATEUR` (étape d'initiation via l'historique), `ETAPE_PRECEDENTE` (dernière étape traversée), `RESPONSABLE_TRAITEMENT` (étape où le traitement a été fait, mémorisée par la transition `setsHandler`, voir ci-dessous) |
| `condition` | string | Expression SpEL **facultative** ; absente = transition inconditionnelle (repli évalué en dernier) |
| `setsHandler` | bool | (facultatif) **Marqueur de traitement** : au franchissement de cette transition, le moteur mémorise l'acteur (`handler_actor_id`) et l'étape d'origine (`handler_step_key`) sur le dossier. Cible de la reprise `RESPONSABLE_TRAITEMENT` (traitement délégué **ou** direct). **Obligatoire** (au moins une transition marquée) si une transition utilise la cible `RESPONSABLE_TRAITEMENT` (sinon 400) ; plusieurs transitions marquées sont autorisées (délégation et traitement direct) |

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

**Champs inconnus refusés** : tout champ non reconnu (faute de frappe comprise) est
rejeté à l'import (400) avec son nom et son chemin — une erreur de saisie ne doit pas
désactiver une règle en silence.

## Versionnage et idempotence

- Chaque contenu nouveau crée la version suivante ; les dossiers en cours gardent leur version.
- L'empreinte SHA-256 porte sur le contenu canonique (conditions normalisées, listes triées, commentaires et indentation ignorés, `version` exclu) : un réimport à l'identique renvoie HTTP 200 sans nouvelle version ; un changement réel crée N+1.
- Chaque import écrit une ligne d'audit (`workflow_import_audit`) : clé, version, auteur authentifié, horodatage UTC, empreinte, nom de fichier, contenu conservé, indicateur de création.
- L'import exige un **administrateur nommé** (identité préfixée par l'émetteur, `nomEmetteur:sub`, déclarée dans `auth.admin.subjects`), sinon 403. Voir `docs/authentification.md`.
