import type { JsonSchema, UiSchema } from '../api/types'
import { formFields, type FormField } from './formSchema'

/**
 * Analyse du JSON Schema saisi dans l'editeur d'administration. L'analyse est LOCALE et ne
 * sert qu'a l'apercu et au confort de saisie : le serveur reste seul juge de ce qui est
 * publiable (FRM-01), et c'est son message d'erreur qui est affiche en cas de refus. On ne
 * devance donc pas ses regles metier, on se contente de dire « ce texte n'est pas un objet
 * JSON » plutot que de laisser l'apercu echouer silencieusement.
 */
export interface SchemaText {
  /** Objet JSON Schema analyse, ou null si le texte n'en est pas un. */
  schema: JsonSchema | null
  /** UI Schema analyse (objet libre), ou null. */
  uiSchema: UiSchema | null
  /** Message d'erreur lisible, ou null si les deux textes sont exploitables. */
  error: string | null
}

/** Analyse le texte d'un JSON Schema et celui d'un UI Schema (facultatif). */
export function parseSchemaText(jsonText: string, uiText: string): SchemaText {
  let schema: JsonSchema | null = null
  let uiSchema: UiSchema | null = null

  const trimmedUi = uiText.trim()
  if (trimmedUi.length > 0) {
    const parsed = parseObject(trimmedUi, 'UI Schema')
    if ('error' in parsed) {
      return { schema: null, uiSchema: null, error: parsed.error }
    }
    uiSchema = parsed.value as UiSchema
  }

  const trimmedJson = jsonText.trim()
  if (trimmedJson.length === 0) {
    return {
      schema: null,
      uiSchema,
      error: 'Le JSON Schema est vide. Renseignez au moins un objet racine « type: object » avec ses champs.',
    }
  }
  const parsed = parseObject(trimmedJson, 'JSON Schema')
  if ('error' in parsed) {
    return { schema: null, uiSchema, error: parsed.error }
  }
  schema = parsed.value as JsonSchema

  if (schema.type !== undefined && schema.type !== 'object') {
    return {
      schema,
      uiSchema,
      error: `La racine du formulaire doit etre un objet (« type: object ») ; recu « ${schema.type} ».`,
    }
  }
  return { schema, uiSchema, error: null }
}

/** Champs affichables d'un texte de schema (apercu et liaison de variables). */
export function previewFields(parsed: SchemaText): FormField[] {
  if (!parsed.schema) {
    return []
  }
  return formFields(parsed.schema, parsed.uiSchema)
}

/** Forme attendue, montree en indication : des champs, aucun contenu metier impose. */
export const SCHEMA_PLACEHOLDER = `{
  "type": "object",
  "title": "Titre du formulaire",
  "properties": {
    "champ": { "type": "string", "title": "Libelle du champ" }
  },
  "required": ["champ"]
}`

export const UI_SCHEMA_PLACEHOLDER = `{
  "ui:order": ["champ"]
}`

/** Une analyse reussie, ou le message a afficher. */
type Parsed = { value: Record<string, unknown> } | { error: string }

function parseObject(text: string, label: string): Parsed {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch (cause) {
    const detail = cause instanceof Error ? cause.message : String(cause)
    return { error: `${label} : JSON invalide (${detail}).` }
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { error: `${label} : un objet JSON est attendu (recu ${describe(value)}).` }
  }
  return { value: value as Record<string, unknown> }
}

function describe(value: unknown): string {
  if (value === null) {
    return 'null'
  }
  return Array.isArray(value) ? 'un tableau' : typeof value
}
