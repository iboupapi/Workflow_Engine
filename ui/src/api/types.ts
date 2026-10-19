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
 * Phase 3 — demandes d'usagers (REQ-01, REQ-02, REQ-03).
 *
 * Ces contrats ne figurent pas encore dans schema.d.ts : ils sont recopies a la main
 * depuis les records exposes par RequestSubmissionService. A regenerer avec
 * `npm run api:generate` (backend demarre) pour supprimer cette duplication.
 */

/** Type de demande propose a l'usager (referentiel publie). */
export interface RequestTypeView {
  id: string
  key: string
  label: string
  category: string | null
}

/** Formulaire de la version publiee en vigueur : JSON Schema 2020-12 + UI Schema. */
export interface RequestFormView {
  typeKey: string
  label: string
  version: number
  schema: JsonSchema
  uiSchema: UiSchema | null
}

/** Statut d'une saisie d'usager. */
export type SubmissionStatus = 'DRAFT' | 'SUBMITTED'

/** Saisie d'usager : brouillon reprenable ou demande soumise, liee a son dossier. */
export interface SubmissionView {
  id: string
  typeKey: string
  version: number
  status: SubmissionStatus
  instanceId: string | null
  createdAt: string
  submittedAt: string | null
}

/** Corps des endpoints de saisie : type vise et champs du formulaire. */
export interface FormDataRequest {
  typeKey: string
  formData: Record<string, unknown>
}

/** Brouillon complet : la saisie enregistree, pour reprendre le formulaire. */
export interface DraftView {
  id: string
  typeKey: string
  version: number
  formData: Record<string, unknown>
  createdAt: string
}

/** Verdict de l'analyse antivirus d'une piece jointe (FRM-05). */
export type AttachmentScanStatus = 'PENDING' | 'CLEAN' | 'INFECTED' | 'ERROR'

/**
 * Piece jointe d'une demande (FRM-05) : le contenu n'est jamais dans la reponse, seules
 * les metadonnees servent au rendu, et le verdict conditionne la mise a disposition.
 */
export interface AttachmentView {
  id: string
  submissionId: string
  fieldPath: string
  filename: string
  mimeType: string
  sizeBytes: number
  sha256: string
  scanStatus: AttachmentScanStatus
  createdAt: string
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
