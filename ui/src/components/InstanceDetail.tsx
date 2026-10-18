import { useState } from 'react'
import { useActions, useHistory, useInstance, useTransition } from '../api/hooks'
import { statusTone } from '../lib/status'
import { Badge, ErrorNote, Panel } from './ui'

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString('fr-FR')
}

/** Detail d'un dossier : etat, variables, actions possibles et historique. */
export function InstanceDetail({ id }: { id: string }) {
  const instance = useInstance(id)
  const actions = useActions(id)
  const history = useHistory(id)
  const transition = useTransition(id)
  const [variablesJson, setVariablesJson] = useState('{}')
  const [comment, setComment] = useState('')

  function run(action: string): void {
    let variables: Record<string, unknown> | undefined
    try {
      variables = variablesJson.trim() === '' ? undefined : (JSON.parse(variablesJson) as Record<string, unknown>)
    } catch {
      return
    }
    transition.mutate({ action, variables, comment: comment === '' ? undefined : comment })
  }

  return (
    <Panel
      title="Détail du dossier"
      actions={instance.data ? <Badge tone={statusTone(instance.data.status)}>{instance.data.status}</Badge> : undefined}
    >
      {instance.isLoading && <p className="text-sm text-slate-400">Chargement…</p>}
      <ErrorNote error={instance.error} />

      {instance.data && (
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-slate-400">Étape courante</dt>
          <dd className="font-medium text-slate-100">{instance.data.currentStepKey}</dd>
          <dt className="text-slate-400">Imputé à</dt>
          <dd className="text-slate-200">{instance.data.assigneeId ?? '—'}</dd>
          <dt className="text-slate-400">Initiateur</dt>
          <dd className="text-slate-200">{instance.data.initiatorId ?? '—'}</dd>
          <dt className="text-slate-400">Circuit</dt>
          <dd className="text-slate-200">
            {instance.data.definitionKey} v{instance.data.definitionVersion}
          </dd>
        </dl>
      )}

      {instance.data && (
        <details className="mt-3">
          <summary className="cursor-pointer text-sm text-slate-400">Variables du dossier</summary>
          <pre className="mt-2 overflow-auto rounded-md bg-slate-950 p-3 text-xs text-slate-300">
            {JSON.stringify(instance.data.variables ?? {}, null, 2)}
          </pre>
        </details>
      )}

      <div className="mt-4 border-t border-slate-800 pt-4">
        <h3 className="mb-2 text-xs font-semibold tracking-wide text-slate-400 uppercase">
          Actions possibles
        </h3>
        <ErrorNote error={actions.error} />
        <div className="flex flex-wrap gap-2">
          {actions.data?.map((option) => (
            <button
              key={option.action}
              type="button"
              disabled={transition.isPending}
              onClick={() => run(option.action)}
              className="rounded-md border border-slate-700 px-3 py-1.5 text-sm text-slate-100 hover:bg-slate-800 disabled:opacity-50"
            >
              {option.action}
              {option.requiredData.length > 0 && (
                <span className="ml-2 text-xs text-amber-400">
                  {option.requiredData.join(', ')}
                </span>
              )}
            </button>
          ))}
          {actions.data && actions.data.length === 0 && (
            <p className="text-sm text-slate-400">Dossier terminé : aucune action.</p>
          )}
        </div>

        <div className="mt-3 grid gap-2">
          <label className="text-xs text-slate-400" htmlFor="transition-variables">
            Variables à fusionner (JSON, facultatif)
          </label>
          <textarea
            id="transition-variables"
            value={variablesJson}
            onChange={(event) => setVariablesJson(event.target.value)}
            rows={3}
            className="rounded-md border border-slate-700 bg-slate-950 p-2 font-mono text-xs text-slate-200"
          />
          <input
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder="Commentaire (obligatoire pour un rejet)"
            className="rounded-md border border-slate-700 bg-slate-950 p-2 text-sm text-slate-200"
          />
        </div>
        <ErrorNote error={transition.error} />
      </div>

      <div className="mt-4 border-t border-slate-800 pt-4">
        <h3 className="mb-2 text-xs font-semibold tracking-wide text-slate-400 uppercase">
          Historique
        </h3>
        <ErrorNote error={history.error} />
        <ol className="space-y-2">
          {history.data?.map((entry) => (
            <li key={entry.id} className="text-sm">
              <div className="flex items-center gap-2">
                <Badge tone="neutral">{entry.action}</Badge>
                <span className="font-mono text-xs text-slate-400">
                  {entry.fromStep ?? '—'} → {entry.toStep ?? '—'}
                </span>
              </div>
              <div className="text-xs text-slate-500">
                {formatTimestamp(entry.timestamp)} · {entry.actorId ?? '—'}
                {entry.comment ? ` · « ${entry.comment} »` : ''}
              </div>
            </li>
          ))}
        </ol>
      </div>
    </Panel>
  )
}
