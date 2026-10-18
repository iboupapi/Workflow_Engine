import { useMemo, useState, type FormEvent } from 'react'
import { useDefinition, useStartWorkflow } from '../api/hooks'
import type { VariableDeclaration } from '../api/types'
import { ErrorNote, Panel } from './ui'

type RawValue = string | boolean

function coerce(declaration: VariableDeclaration, raw: RawValue): unknown {
  if (declaration.type === 'boolean') {
    return Boolean(raw)
  }
  if (declaration.type === 'number') {
    return Number(raw)
  }
  return raw
}

/** Ecran de creation d'une demande (initiation d'un dossier). */
export function NewRequestView({
  definitionKey,
  authenticated,
}: {
  definitionKey: string
  authenticated: boolean
}) {
  const definition = useDefinition(definitionKey, authenticated)
  const start = useStartWorkflow(definitionKey)
  const [startStepKey, setStartStepKey] = useState('')
  const [values, setValues] = useState<Record<string, RawValue>>({})
  const [message, setMessage] = useState<string | null>(null)

  const startSteps = useMemo(() => {
    const declared = new Set(
      definition.data?.entryPoints.map((entry) => entry.startStepKey) ?? [],
    )
    return (definition.data?.steps ?? []).filter(
      (step) => step.type === 'START' || declared.has(step.key),
    )
  }, [definition.data])

  function set(name: string, value: RawValue): void {
    setValues((previous) => ({ ...previous, [name]: value }))
  }

  function onSubmit(event: FormEvent): void {
    event.preventDefault()
    setMessage(null)
    const declarations = definition.data?.variables ?? []
    const variables: Record<string, unknown> = {}
    for (const declaration of declarations) {
      const raw = values[declaration.key]
      if (raw === undefined || raw === '') {
        if (declaration.required) {
          setMessage(`Donnée obligatoire manquante : ${declaration.key}.`)
          return
        }
        continue
      }
      variables[declaration.key] = coerce(declaration, raw)
    }
    start.mutate({
      startStepKey: startStepKey === '' ? undefined : startStepKey,
      variables,
    })
  }

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-6 text-sm text-slate-400">
        Choisissez un jeton (rôle initiateur, ex. CITOYEN) pour créer une demande.
      </div>
    )
  }

  return (
    <Panel title="Nouvelle demande">
      {definition.isLoading && <p className="text-sm text-slate-400">Chargement du circuit…</p>}
      <ErrorNote error={definition.error} />

      <form onSubmit={onSubmit} className="grid max-w-xl gap-3">
        <label className="grid gap-1 text-sm">
          <span className="text-slate-400">Étape de départ</span>
          <select
            value={startStepKey}
            onChange={(event) => setStartStepKey(event.target.value)}
            className="rounded-md border border-slate-700 bg-slate-950 p-2 text-slate-100"
          >
            <option value="">Défaut (première étape START)</option>
            {startSteps.map((step) => (
              <option key={step.key} value={step.key}>
                {step.key}
                {step.name ? ` — ${step.name}` : ''}
              </option>
            ))}
          </select>
        </label>

        {(definition.data?.variables ?? []).map((declaration) => (
          <label key={declaration.key} className="grid gap-1 text-sm">
            <span className="text-slate-400">
              {declaration.key}
              <span className="ml-2 text-xs text-slate-500">
                {declaration.type}
                {declaration.required ? ' · obligatoire' : ''}
              </span>
            </span>
            {declaration.type === 'boolean' ? (
              <input
                type="checkbox"
                checked={Boolean(values[declaration.key])}
                onChange={(event) => set(declaration.key, event.target.checked)}
                className="h-4 w-4"
              />
            ) : (
              <input
                type={declaration.type === 'number' ? 'number' : declaration.type === 'date' ? 'date' : 'text'}
                value={typeof values[declaration.key] === 'string' ? (values[declaration.key] as string) : ''}
                onChange={(event) => set(declaration.key, event.target.value)}
                className="rounded-md border border-slate-700 bg-slate-950 p-2 text-slate-100"
              />
            )}
          </label>
        ))}

        {message && (
          <p className="rounded-md border border-amber-700 bg-amber-950/60 px-3 py-2 text-sm text-amber-200">
            {message}
          </p>
        )}
        <ErrorNote error={start.error} />

        <button
          type="submit"
          disabled={start.isPending}
          className="justify-self-start rounded-md bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-500 disabled:opacity-50"
        >
          {start.isPending ? 'Création…' : 'Créer la demande'}
        </button>
      </form>

      {start.data && (
        <p className="mt-4 rounded-md border border-emerald-800 bg-emerald-950/60 px-3 py-2 text-sm text-emerald-200">
          Dossier créé : <span className="font-mono">{start.data.id}</span> (étape{' '}
          {start.data.currentStepKey})
        </p>
      )}
    </Panel>
  )
}
