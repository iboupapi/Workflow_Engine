import type { components } from './schema'

type Schemas = components['schemas']

/**
 * Champs que le moteur renvoie TOUJOURS, meme si le schema OpenAPI les marque
 * optionnels (Springdoc ne deduit pas les champs obligatoires). On se sert du schema
 * genere comme source de verite, puis on retablit les champs presents en reponse.
 */
type Present<T> = { [K in keyof T]-?: Exclude<T[K], undefined> }

export type InstanceStatus = NonNullable<Schemas['WorkflowInstanceResponse']['status']>

export type StepType = NonNullable<Schemas['StepView']['type']>

/** Vues de la recherche de dossiers (parametre de requete, absent du schema des entites). */
export type InstanceView = 'MINE' | 'INBOX' | 'ALL'

/** Dossier en circulation. */
export type WorkflowInstance = Present<Schemas['WorkflowInstanceResponse']>

export type StepView = Present<Schemas['StepView']>

export type TransitionView = Present<Schemas['TransitionView']>

export type EntryPoint = Present<Schemas['EntryPoint']>

export type VariableDeclaration = Present<Schemas['VariableDeclaration']>

export type ActionOption = Present<Schemas['ActionOption']>

export type HistoryEntry = Present<Schemas['HistoryResponse']>

/** Jeton emis par l'endpoint de developpement (SEC-08). */
export type DevToken = Present<Schemas['DevToken']>

/** Corps de requete : tous les champs restent facultatifs (le schema fait foi). */
export type StartWorkflowRequest = Schemas['StartWorkflowRequest']

export type TransitionRequest = Schemas['TransitionRequest']

/** Carte statique d'un circuit, avec ses collections en types presents. */
export interface DefinitionView
  extends Omit<
    Present<Schemas['DefinitionView']>,
    'entryPoints' | 'variables' | 'steps' | 'transitions'
  > {
  entryPoints: EntryPoint[]
  variables: VariableDeclaration[]
  steps: StepView[]
  transitions: TransitionView[]
}

/** Page Spring Data (enveloppe du moteur), generique sur le contenu. */
export type SpringPage<T> = Omit<Present<Schemas['PageWorkflowInstanceResponse']>, 'content'> & {
  content: T[]
}

/*
 * Phase 3 — demandes d'usagers (REQ-01, REQ-02, REQ-03) et espace d'administration.
 *
 * Contrats derives de schema.d.ts (regenerable par `npm run api:generate`, backend
 * demarre). Springdoc ne deduit pas les champs obligatoires : `Present<T>` retablit les
 * champs presents en reponse, et les seuls champs que le serveur peut renvoyer a `null`
 * sont redeclares explicitement — le rendu ne doit pas croire pouvoir appeler une methode
 * sur une categorie absente.
 */

/** Type de demande propose a l'usager (referentiel publie). */
export type RequestTypeView = Omit<Present<Schemas['TypeView']>, 'category'> & {
  category: string | null
}

/**
 * Formulaire de la version publiee en vigueur : JSON Schema 2020-12 + UI Schema. Le schema
 * est type localement : OpenAPI ne sait pas decrire un JSON Schema, le contrat genere ne le
 * voit que comme un objet libre.
 */
export interface RequestFormView
  extends Omit<Present<Schemas['FormView']>, 'schema' | 'uiSchema'> {
  schema: JsonSchema
  uiSchema: UiSchema | null
}

/** Statut d'une saisie d'usager. */
export type SubmissionStatus = NonNullable<Schemas['SubmissionView']['status']>

/** Saisie d'usager : brouillon reprenable ou demande soumise, liee a son dossier. */
export type SubmissionView = Omit<
  Present<Schemas['SubmissionView']>,
  'status' | 'instanceId' | 'submittedAt'
> & {
  status: SubmissionStatus
  instanceId: string | null
  submittedAt: string | null
}

/** Corps des endpoints de saisie : type vise et champs du formulaire. */
export type FormDataRequest = Schemas['FormDataRequest']

/** Brouillon complet : la saisie enregistree, pour reprendre le formulaire. */
export type DraftView = Omit<Present<Schemas['DraftView']>, 'formData'> & {
  formData: Record<string, unknown>
}

/** Verdict de l'analyse antivirus d'une piece jointe (FRM-05). */
export type AttachmentScanStatus = NonNullable<Schemas['AttachmentView']['scanStatus']>

/**
 * Piece jointe d'une demande (FRM-05) : le contenu n'est jamais dans la reponse, seules
 * les metadonnees servent au rendu, et le verdict conditionne la mise a disposition.
 */
export type AttachmentView = Present<Schemas['AttachmentView']>

/* --------------------------------------------------------------------------------------
 * Espace d'administration des E-Services (REQ-01, REQ-02, FRM-01).
 * ------------------------------------------------------------------------------------ */

/** Statut du referentiel d'un type de demande. */
export type RequestTypeStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED'

/** Ligne du tableau de bord d'administration : referentiel complet, tous statuts. */
export type AdminRequestTypeView = Omit<
  Present<Schemas['AdminRequestTypeView']>,
  'category' | 'status' | 'version' | 'publishedAt' | 'definitionKey'
> & {
  category: string | null
  status: RequestTypeStatus
  /** Renseignes des qu'une version est publiee ; nuls pour un brouillon jamais publie. */
  version: number | null
  publishedAt: string | null
  definitionKey: string | null
}

/** Type de demande rouvert dans l'editeur : version en vigueur et son formulaire. */
export type AdminRequestTypeDetailView = Omit<
  Present<Schemas['AdminRequestTypeDetailView']>,
  'category' | 'status' | 'version' | 'publishedAt' | 'definitionKey' | 'fieldMapping'
  | 'jsonSchema' | 'uiSchema'
> & {
  category: string | null
  status: RequestTypeStatus
  version: number | null
  publishedAt: string | null
  definitionKey: string | null
  fieldMapping: Record<string, string> | null
  jsonSchema: JsonSchema | null
  uiSchema: UiSchema | null
}

/** Schema de formulaire enregistre : son identifiant sert a la publication. */
export type FormSchemaView = Omit<
  Present<Schemas['FormSchemaView']>,
  'jsonSchema' | 'uiSchema'
> & {
  jsonSchema: JsonSchema
  uiSchema: UiSchema | null
}

/** Circuit deploye proposable a la publication (derniere version de chaque cle). */
export type DeployedWorkflowView = Present<Schemas['DeployedWorkflowView']>

/** Corps des endpoints d'administration : tous les champs restent facultatifs. */
export type CreateRequestTypeRequest = Schemas['CreateRequestTypeRequest']

/**
 * Formulaire soumis a la publication. Le JSON Schema est type localement (comme pour
 * RequestFormView) et la liaison de champs est un objet plat champ -> variable moteur.
 */
export type CreateFormSchemaRequest = Omit<
  Schemas['CreateFormSchemaRequest'],
  'schemaJson' | 'uiSchema'
> & {
  schemaJson?: JsonSchema
  uiSchema?: UiSchema | null
}

export type PublishRequestTypeVersionRequest = Omit<
  Schemas['PublishRequestTypeVersionRequest'],
  'fieldMapping'
> & {
  fieldMapping?: Record<string, string>
}

/** Sous-ensemble du JSON Schema utilise par le rendu (les autres mots-cles sont ignores). */
export interface JsonSchema {
  type?: string
  title?: string
  description?: string
  properties?: Record<string, JsonSchemaProperty>
  required?: string[]
}

export interface JsonSchemaProperty {
  type?: string
  title?: string
  description?: string
  format?: string
  enum?: unknown[]
  items?: JsonSchemaProperty
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  pattern?: string
  default?: unknown
}

/** UI Schema : presentation par champ, plus l'ordre souhaite des champs. */
export interface UiSchema {
  'ui:order'?: string[]
  [field: string]: UiSchemaField | string[] | undefined
}

export interface UiSchemaField {
  label?: string
  help?: string
  widget?: string
}
