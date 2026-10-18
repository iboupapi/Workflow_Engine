# Authentification et contrôle par rôle d'étape (cahier de recette, point 2)

Sans identifiants : **401** ; identifié sans le droit : **403** ; documentation publique.
Aucun nouvel endpoint n'est public par oubli (refus par défaut).

## Mécanismes en place

| Zone            | Identité acceptée                                   | Rôle requis |
|-----------------|-----------------------------------------------------|-------------|
| `/api/admin/**` | JWT d'un émetteur configuré, identité **administrateur nommé** (`nomEmetteur:sub`) | `ADMIN` |
| `/api/**`       | JWT d'un émetteur configuré                          | authentifié + rôle d'étape |
| Swagger, `/v3/api-docs`, `/error` | —                                    | public      |

Le **jeton fixe administrateur** (`X-Admin-Token`) a été **retiré**. L'administration
exige désormais un **administrateur nommé** : une identité réelle **préfixée par son
émetteur** (`nomEmetteur:sub`, SEC-03, voir ci-dessous) déclarée dans
`auth.admin.subjects`. L'audit d'import consigne cette identité.

Un administrateur **administre et supervise** ; il **n'est jamais validateur d'étape**
(voir ci-dessous).

## Émetteurs JWT configurables (`auth.jwt.issuers.<nom>`)

| Clé            | Obligatoire | Rôle                                                              |
|----------------|-------------|-------------------------------------------------------------------|
| `issuer`       | oui         | Valeur exacte de la revendication `iss` (aucune découverte réseau) |
| `audience`     | oui         | Valeur exigée dans `aud`                                          |
| `hmac-secret`  | OU `jwks-uri` (exactement un des deux) | Clé partagée (émetteur de test local uniquement) |
| `jwks-uri`     | OU `hmac-secret` | Jeu de clés distant (annuaire, portail)                        |
| `roles-claim`  | non (`roles` par défaut) | Revendication **liste** des rôles, au **niveau racine** du jeton  |
| `role-mapping` | non         | Correspondance **rôle moteur → rôles du jeton** (voir ci-dessous) |

Vérifiés à chaque requête : signature, émetteur, audience, expiration — sinon 401.
Sans émetteur configuré, aucun JWT n'est accepté. L'identité d'exécution est
TOUJOURS l'identité du jeton, **préfixée par l'émetteur** (`nomEmetteur:sub`, SEC-03) :
la valeur `actorId`/`initiatorId` du corps est ignorée dès qu'un JWT est présent (recette 2.5).

### Correspondance des rôles (`role-mapping`)

Les rôles du jeton sont lus comme une **liste**. Chaque émetteur peut déclarer la
correspondance entre ses rôles et les **rôles moteur** employés dans `owners` des étapes :

```yaml
auth:
  jwt:
    issuers:
      annuaire:
        issuer: "https://annuaire.mfp.gouv.sn"
        audience: "workflow-api"
        jwks-uri: "https://annuaire.mfp.gouv.sn/.well-known/jwks.json"
        roles-claim: "roles"                  # claim liste à la racine (voir ci-dessous)
        role-mapping:
          CHEF_DIVISION: ["GRADE_CHEF_DIV", "ROLE_CHEFDIV"]
          DGFP:          ["DAFP_DGFP"]
```

#### Format de `roles-claim`

Le moteur lit `roles-claim` comme un **tableau de chaînes au niveau racine** du
payload JWT : `{"roles": ["AGENT_BUREAU", "CHEF_DIVISION"]}` ou `{"groups": [...]}`.

**Claims imbriqués :** les chemins à points (type `realm_access.roles`) ne sont
**pas** résolus. L'émetteur SSO doit soit exposer la liste à la racine du jeton,
soit la remonter via une règle de mapping Keycloak afin qu'elle apparaisse comme
une revendication de premier niveau.

Un rôle moteur est accordé dès que le jeton porte l'un des rôles listés. **Sans**
`role-mapping`, **aucun rôle n'est accordé** (défaut sûr : jamais l'identité du jeton).
Un rôle du jeton **non reconnu n'accorde aucun droit**, et le rôle réservé `ADMIN` n'est
**jamais** dérivé du jeton (seuls les identités nommées de `auth.admin.subjects` le reçoivent).

## Identité préfixée par l'émetteur (SEC-03)

L'identité d'exécution d'un jeton est **toujours préfixée par le nom de l'émetteur** :
`nomEmetteur:sub`, où `nomEmetteur` est la **clé de configuration** de l'émetteur
(`auth.jwt.issuers.<nom>`, ex. `agents`, `usagers`) — et non l'adresse `iss` complète,
trop longue et susceptible de changer. Le séparateur est le **premier** `:` (un nom
d'émetteur ne doit donc pas contenir `:`). Exemples : `agents:agent-001`,
`usagers:citoyen-123`.

Conséquences :

- deux émetteurs aux `sub` identiques ne peuvent **pas** se confondre (histoires,
  plafonds, imputations) ;
- la liste `auth.admin.subjects` contient des identités **préfixées**
  (`agents:matricule-admin`) et le contrôle d'administrateur compare l'identité
  **complète**, émetteur inclus — un jeton du portail citoyen dont le `sub` serait
  celui d'un administrateur d'un autre émetteur **n'est pas** administrateur ;
- les valeurs `actorId`/`initiatorId` du corps restent ignorées (recette 2.5).

**Historique existant :** les lignes antérieures à SEC-03 conservent l'ancien format
(`sub` seul). L'historique étant **immuable**, ces lignes ne sont **pas** réécrites :
`schema_version`/en-tête de trace devra le noter côté applications consommatrices.
Les colonnes d'identité ont été élargies à `varchar(512)` (migration `V11`) pour
absorber le préfixe.

## Contrôle par rôle d'étape (YAML versionné)

Les rôles responsables d'une étape sont déclarés dans le YAML (`owners`), versionné
avec le circuit. Ils gouvernent deux choses :

- la **boîte de réception** (jeu de dossiers en attente pour un rôle) ;
- le **droit d'agir** : `POST /api/workflows/{key}/start` exige un rôle responsable de
  l'**étape de départ effective** ; `POST /api/instances/{id}/transition` exige un rôle
  responsable de l'**étape courante**. Sinon **403**.

Règles :

- le rôle réservé **`ADMIN` est interdit** dans `owners` (refusé à l'import) : un
  administrateur **ne valide pas** une étape, même s'il détient `ADMIN` ;
- une étape **sans `owners`** ne peut être ni démarrée ni franchie (refus par défaut) ;
- un **rôle inconnu** (hors correspondance de l'émetteur) ne donne aucun droit ;
- l'administrateur (`ADMIN`) **voit tout** (lecture, vue `ALL`) mais **n'agit sur aucune étape**.

## Lecture des dossiers (section 3)

Un dossier est visible si le demandeur est : **initiateur**, **personne désignée**
(assigné), **administrateur**, ou détenteur d'un rôle responsable de l'**étape courante
ou d'une étape traversée** (historique). Un dossier imputé à quelqu'un d'autre sort de la
portée des collègues de rôle. Invisible = **404**.

## Émetteur de test

```yaml
auth:
  admin:
    subjects: ["test:admin-01"]   # identité préfixée par l'émetteur (SEC-03)
  jwt:
    issuers:
      test:
        issuer: "https://test.mfp.local"
        audience: "workflow-api"
        hmac-secret: ${TEST_ISSUER_SECRET:}  # test local uniquement
        roles-claim: "roles"
```

Jetons forgés en test par `JwtTestTokens` (Nimbus, HS256) ; jetons manuels via
`scripts/generate-test-tokens.ps1` (agent, citoyen, administrateur). À confirmer avec le
ministère avant le pilote (§14) : annuaire/SSO des agents et portail citoyen
(protocoles, émetteurs, audiences, noms des revendications de rôles), division/service
portée par le jeton, et arbitrage moteur/applications pour la vérification.
