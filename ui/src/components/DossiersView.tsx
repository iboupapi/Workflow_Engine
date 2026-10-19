import { useState } from 'react'
import { useInstances } from '../api/hooks'
import type { InstanceView } from '../api/types'
import { statusTone } from '../lib/status'
import { InstanceDetail } from './InstanceDetail'
import { Badge, ErrorNote, Panel } from './ui'

const VIEWS: { id: InstanceView; label: string; hint: string }[] = [
  { id: 'MINE', label: 'MINE', hint: 'Mes dossiers (initiateur ou imputé)' },
  { id: 'INBOX', label: 'INBOX', hint: 'Boîte de réception de mes rôles' },
  { id: 'ALL', label: 'ALL', hint: 'Vue complète — réservée aux administrateurs / auditeurs' },
]

/** Liste des dossiers (bannettes MINE / INBOX / ALL) et detail du dossier selectionne. */
export function DossiersView({ authenticated }: { authenticated: boolean }) {
  const [view, setView] = useState<InstanceView>('MINE')
  const [selected, setSelected] = useState<string | null>(null)
  const { data, isLoading, error } = useInstances(view, authenticated)

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-line bg-surface-muted p-6 text-sm text-ink-muted">
        Choisissez un jeton de test ci-dessus pour consulter les dossiers.
      </div>
    )
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <Panel
        title="Dossiers"
        actions={
          <div className="flex gap-1">
            {VIEWS.map((candidate) => (
              <button
                key={candidate.id}
                type="button"
                title={candidate.hint}
                onClick={() => setView(candidate.id)}
                className={`rounded-md border px-2 py-1 text-xs ${
                  view === candidate.id
                    ? 'border-mfp-green bg-mfp-green/10 font-medium text-mfp-green'
                    : 'border-line-strong text-ink hover:bg-surface-muted'
                }`}
              >
                {candidate.label}
              </button>
            ))}
          </div>
        }
      >
        {isLoading && <p className="text-sm text-ink-muted">Chargement…</p>}
        <ErrorNote error={error} />
        <ul className="divide-y divide-line">
          {data?.content.map((instance) => (
            <li key={instance.id}>
              <button
                type="button"
                onClick={() => setSelected(instance.id)}
                className={`flex w-full flex-col gap-1 px-2 py-3 text-left hover:bg-surface-muted ${
                  selected === instance.id ? 'bg-mfp-green/5' : ''
                }`}
              >
                <span className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs text-ink-muted">
                    {instance.id.slice(0, 8)}
                  </span>
                  <Badge tone={statusTone(instance.status)}>{instance.status}</Badge>
                </span>
                <span className="text-sm text-ink">
                  {instance.definitionKey} · étape{' '}
                  <span className="font-medium">{instance.currentStepKey}</span>
                </span>
                <span className="text-xs text-ink-muted">
                  initiateur {instance.initiatorId ?? '—'}
                  {instance.assigneeId ? ` · imputé ${instance.assigneeId}` : ''}
                </span>
              </button>
            </li>
          ))}
        </ul>
        {data && data.content.length === 0 && (
          <p className="text-sm text-ink-muted">Aucun dossier dans cette vue.</p>
        )}
      </Panel>

      {selected ? (
        <InstanceDetail id={selected} />
      ) : (
        <Panel title="Détail">
          <p className="text-sm text-ink-muted">Sélectionnez un dossier.</p>
        </Panel>
      )}
    </div>
  )
}
