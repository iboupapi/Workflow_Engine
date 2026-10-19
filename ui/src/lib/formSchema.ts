import type { JsonSchema, JsonSchemaProperty, UiSchema, UiSchemaField } from '../api/types'

/** Widget de saisie retenu pour un champ (le rendu ne connait que ces formes). */
export type FieldWidget =
  | 'text'
  | 'textarea'
  | 'number'
  | 'checkbox'
  | 'date'
  | 'email'
  | 'select'
  | 'multiselect'

/** Champ du formulaire, pret a etre rendu. */
export interface FormField {
  name: string
  label: string
  help: string | null
  widget: FieldWidget
  required: boolean
  /** Valeurs proposees par un `enum` (select et multiselect). */
  options: { value: string; label: string }[]
  property: JsonSchemaProperty
}

/**
 * Ordre des champs : celui declare dans le UI Schema (`ui:order`), puis les champs
 * restants dans l'ordre du JSON Schema. Un champ absent du schema est ignore.
 */
export function formFields(schema: JsonSchema | undefined, uiSchema: UiSchema | null): FormField[] {
  const properties = schema?.properties ?? {}
  const required = new Set(schema?.required ?? [])
  const declaredOrder = Array.isArray(uiSchema?.['ui:order'])
    ? (uiSchema?.['ui:order'] as string[])
    : []
  const names = [
    ...declaredOrder.filter((name) => name in properties),
    ...Object.keys(properties).filter((name) => !declaredOrder.includes(name)),
  ]
  return names.map((name) =>
    describe(name, properties[name], required.has(name), uiField(uiSchema, name)),
  )
}

function uiField(uiSchema: UiSchema | null, name: string): UiSchemaField {
  const candidate = uiSchema?.[name]
  return candidate !== null && typeof candidate === 'object' && !Array.isArray(candidate)
    ? (candidate as UiSchemaField)
    : {}
}

function describe(
  name: string,
  property: JsonSchemaProperty,
  required: boolean,
  ui: UiSchemaField,
): FormField {
  return {
    name,
    label: ui.label ?? property.title ?? humanize(name),
    help: ui.help ?? property.description ?? null,
    widget: widgetOf(property, ui),
    required,
    options: optionsOf(property),
    property,
  }
}

function widgetOf(property: JsonSchemaProperty, ui: UiSchemaField): FieldWidget {
  if (ui.widget === 'textarea' || ui.widget === 'text') {
    return ui.widget
  }
  if (Array.isArray(property.enum)) {
    return 'select'
  }
  if (property.type === 'boolean') {
    return 'checkbox'
  }
  if (property.type === 'number' || property.type === 'integer') {
    return 'number'
  }
  if (property.type === 'array' && property.items?.enum) {
    return 'multiselect'
  }
  if (property.format === 'date') {
    return 'date'
  }
  if (property.format === 'email') {
    return 'email'
  }
  if (property.format === 'textarea') {
    return 'textarea'
  }
  return 'text'
}

function optionsOf(property: JsonSchemaProperty): { value: string; label: string }[] {
  const values = property.enum ?? property.items?.enum ?? []
  return values.map((value) => ({ value: String(value), label: String(value) }))
}

/** Libelle par defaut d'un champ non decrit : `typeActe` devient « Type acte ». */
export function humanize(name: string): string {
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** Valeurs initiales : valeurs par defaut du schema, completees par un brouillon. */
export function initialValues(
  schema: JsonSchema | undefined,
  saved: Record<string, unknown> | null,
): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const [name, property] of Object.entries(schema?.properties ?? {})) {
    if (property.default !== undefined) {
      values[name] = property.default
    }
  }
  return { ...values, ...(saved ?? {}) }
}

/**
 * Convertit la saisie en corps de requete : les champs laisses vides sont omis, ce qui
 * laisse au schema serveur le soin de signaler une donnee obligatoire manquante (FRM-03).
 */
export function toFormData(
  fields: FormField[],
  values: Record<string, unknown>,
): Record<string, unknown> {
  const data: Record<string, unknown> = {}
  for (const field of fields) {
    const value = coerce(field, values[field.name])
    if (value !== undefined) {
      data[field.name] = value
    }
  }
  return data
}

function coerce(field: FormField, raw: unknown): unknown {
  if (field.widget === 'multiselect') {
    return Array.isArray(raw) && raw.length > 0 ? raw : undefined
  }
  if (field.widget === 'checkbox') {
    return typeof raw === 'boolean' ? raw : undefined
  }
  if (raw === undefined || raw === null) {
    return undefined
  }
  const text = String(raw)
  if (text.trim() === '') {
    return undefined
  }
  if (field.widget === 'number') {
    const number = Number(text)
    return Number.isFinite(number) ? number : text
  }
  return text
}
