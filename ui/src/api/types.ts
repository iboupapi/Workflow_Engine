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
